package org.cra.core.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.core.internal.pipeline.AntiAnchorValidator;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.RunBudgetTracker;

/**
 * 验证波编排（07 T2.4）：候选按文件分批 → 每批一个验证 brief（balanced-review）→ 三值判定；INSUFFICIENT 受控补查一次（followup_file
 * 全文注入，03§4.2 判定要求第三条）；LLM 不可得 → 缩小上下文重试 1 次 → 仍失败 该批全 UNAVAILABLE（02§4.2）；CONFIRMED
 * 过反锚定引用新颖性校验（质控信号 PASS/FAIL 入 trace，不阻断）。
 *
 * <p>批内串行（首轮 → 可选补查轮），批间并行（≤4，04§2.3 验证 wave 并行度）；预算记账在收集线程做。
 */
final class VerificationWave {

  private final SubAgent<VerificationBatchInput> verifier;
  private final AgentRunner agentRunner;
  private final TraceWriter trace;
  private final RunBudgetTracker budget;
  private final ExecutorService pool;
  private final Function<String, Optional<String>> followupLookup;
  private final CitedEvidenceResolver citedResolver;

  /** 批内单 finding 判定结果。 */
  record PerFinding(
      VerdictJsonCodec.ParsedVerdict verdict,
      AntiAnchorValidator.Result antiAnchor,
      boolean followupAttempted,
      String model) {}

  /** 验证波产出。 */
  record WaveResult(
      Map<String, PerFinding> outcomes,
      long tokensIn,
      long tokensOut,
      List<String> degradations,
      int batches) {}

  /**
   * @param verifier 验证者定义（组合根注入）
   * @param agentRunner 执行器
   * @param trace trace 写入器
   * @param budget run 级预算（验证消耗计入）
   * @param pool 批间并行池（pipeline 持有）
   * @param followupLookup 补查文件全文查找（path → head 侧内容；不可得为 empty）
   * @param citedResolver 被引证据解析（context 类分型组装）
   */
  VerificationWave(
      SubAgent<VerificationBatchInput> verifier,
      AgentRunner agentRunner,
      TraceWriter trace,
      RunBudgetTracker budget,
      ExecutorService pool,
      Function<String, Optional<String>> followupLookup,
      CitedEvidenceResolver citedResolver) {
    this.verifier = verifier;
    this.agentRunner = agentRunner;
    this.trace = trace;
    this.budget = budget;
    this.pool = pool;
    this.followupLookup = followupLookup;
    this.citedResolver = citedResolver;
  }

  /**
   * 执行验证波。
   *
   * @param candidates 待验证候选（已过 run 内合并与账本去重）
   * @param policy base 分支 REVIEW.md 策略
   * @param headFiles head 侧文件全文
   * @param reviewDiff 评审范围 diff（切片来源）
   * @param runId run 标识
   * @return finding id → 判定结果
   */
  WaveResult verify(
      List<FindingAggregator.Scored> candidates,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult reviewDiff,
      String runId) {
    Map<String, List<FindingAggregator.Scored>> byFile = new LinkedHashMap<>();
    for (FindingAggregator.Scored scored : candidates) {
      byFile.computeIfAbsent(scored.finding().file(), k -> new ArrayList<>()).add(scored);
    }
    List<Future<BatchOutcome>> futures = new ArrayList<>();
    List<String> degradations = new java.util.concurrent.CopyOnWriteArrayList<>();
    for (Map.Entry<String, List<FindingAggregator.Scored>> entry : byFile.entrySet()) {
      futures.add(
          pool.submit(
              () ->
                  verifyBatch(
                      entry.getKey(),
                      entry.getValue(),
                      policy,
                      headFiles,
                      reviewDiff,
                      runId,
                      degradations)));
    }
    Map<String, PerFinding> outcomes = new LinkedHashMap<>();
    long tokensIn = 0;
    long tokensOut = 0;
    int batches = 0;
    for (Future<BatchOutcome> future : futures) {
      try {
        BatchOutcome outcome = future.get();
        batches++;
        outcomes.putAll(outcome.outcomes());
        tokensIn += outcome.tokensIn();
        tokensOut += outcome.tokensOut();
        budget.record(outcome.tokensIn(), outcome.tokensOut());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("验证波被中断", e);
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        throw new IllegalStateException("验证批次执行失败: " + cause.getMessage(), cause);
      }
    }
    return new WaveResult(outcomes, tokensIn, tokensOut, List.copyOf(degradations), batches);
  }

  private record BatchOutcome(Map<String, PerFinding> outcomes, long tokensIn, long tokensOut) {}

  private BatchOutcome verifyBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult reviewDiff,
      String runId,
      List<String> degradations) {
    if (budget.phase() == RunBudgetTracker.Phase.STOP) {
      // 100% 预算击穿：验证不可得 → UNVERIFIED（02§4.2），不发布
      degradations.add("verifier-budget-exhausted");
      trace.emit(
          "verify",
          Map.of(
              "file_batch",
              file,
              "findings",
              batch.size(),
              "verdicts",
              "unavailable",
              "reason",
              "budget"));
      return new BatchOutcome(allUnavailable(batch), 0, 0);
    }
    CitedEvidenceResolver resolver = citedResolver;
    VerificationBatchInput input =
        VerificationBatchAssembler.buildBatch(
            file, batch, policy, headFiles, reviewDiff, verifier.budget(), resolver);
    AgentBrief brief = verifier.buildBrief(input);
    var persisted = trace.persistBrief(brief, briefSuffix(file));
    trace.emit(
        "agent_start",
        Map.of(
            "agent", verifier.role().id(),
            "file_batch", file,
            "brief_path", persisted.toString()));
    AgentRunner.AgentRunT<VerdictJsonCodec.ParseResult> run =
        agentRunner.executeParsed(
            brief, runId, (json, model) -> adapt(VerdictJsonCodec.parse(json)));
    if (run.llmUnavailable()) {
      // 缩上下文重试 1 次（02§4.2 验证不可得语义的前半段）
      trace.emit("verify_retry", Map.of("file_batch", file, "reason", run.llmError()));
      degradations.add("verifier-retry-once");
      VerificationBatchInput reduced = reduceContext(input);
      AgentRunner.AgentRunT<VerdictJsonCodec.ParseResult> retry =
          agentRunner.executeParsed(
              verifier.buildBrief(reduced),
              runId,
              (json, model) -> adapt(VerdictJsonCodec.parse(json)));
      if (retry.llmUnavailable()) {
        degradations.add("verifier-unavailable");
        trace.emit(
            "verify",
            Map.of(
                "file_batch",
                file,
                "findings",
                batch.size(),
                "verdicts",
                "unavailable",
                "reason",
                "llm"));
        return new BatchOutcome(
            allUnavailable(batch),
            run.tokensIn() + retry.tokensIn(),
            run.tokensOut() + retry.tokensOut());
      }
      run = retry;
    }
    if (run.value() == null) {
      // schema 校验重试耗尽：本批无可信判定 → 全部验证不可得（UNVERIFIED 不发布）
      degradations.add("verifier-schema-violation");
      trace.emit(
          "verify",
          Map.of(
              "file_batch",
              file,
              "findings",
              batch.size(),
              "verdicts",
              "unavailable",
              "reason",
              "schema"));
      return new BatchOutcome(allUnavailable(batch), run.tokensIn(), run.tokensOut());
    }
    // 受控补查（INSUFFICIENT_EVIDENCE → followup_file → 至多一轮）
    Map<String, PerFinding> outcomes = new LinkedHashMap<>();
    VerdictJsonCodec.ParseResult parsed = run.value();
    long tokensIn = run.tokensIn();
    long tokensOut = run.tokensOut();
    Map<String, VerdictJsonCodec.ParsedVerdict> effective = new LinkedHashMap<>(parsed.verdicts());
    for (FindingAggregator.Scored scored : batch) {
      CandidateFinding finding = scored.finding();
      VerdictJsonCodec.ParsedVerdict verdict = effective.get(finding.id());
      boolean followupAttempted = false;
      if (verdict != null
          && verdict.verdict() == VerdictJsonCodec.VerifierVerdict.INSUFFICIENT_EVIDENCE) {
        String followupFile = verdict.followupFile();
        Optional<String> followupText =
            followupFile == null ? Optional.empty() : followupLookup.apply(followupFile);
        trace.emit(
            "verify_followup",
            Map.of(
                "finding_id", finding.id(),
                "file", String.valueOf(followupFile),
                "status", followupText.isPresent() ? "provided" : "unreadable"));
        if (followupText.isPresent()) {
          followupAttempted = true;
          AgentBrief followupBrief =
              verifier.buildBrief(
                  VerificationBatchAssembler.withFollowup(input, followupText.get()));
          trace.persistBrief(followupBrief, briefSuffix(file) + "-fu");
          AgentRunner.AgentRunT<VerdictJsonCodec.ParseResult> followupRun =
              agentRunner.executeParsed(
                  followupBrief, runId, (json, model) -> adapt(VerdictJsonCodec.parse(json)));
          tokensIn += followupRun.tokensIn();
          tokensOut += followupRun.tokensOut();
          if (!followupRun.llmUnavailable() && followupRun.value() != null) {
            VerdictJsonCodec.ParsedVerdict updated =
                followupRun.value().verdicts().get(finding.id());
            if (updated != null) {
              verdict = updated;
            }
          }
        }
      }
      AntiAnchorValidator.Result antiAnchor = null;
      if (verdict != null && verdict.verdict() == VerdictJsonCodec.VerifierVerdict.CONFIRMED) {
        antiAnchor = AntiAnchorValidator.check(findingText(finding), verdict.rationale());
        trace.emit(
            "anti_anchor_check",
            Map.of(
                "finding_id", finding.id(),
                "status", antiAnchor.status().name(),
                "overlap", antiAnchor.overlapRatio(),
                "refs", antiAnchor.refsTotal()));
      }
      outcomes.put(
          finding.id(), new PerFinding(verdict, antiAnchor, followupAttempted, run.model()));
    }
    long confirmed =
        outcomes.values().stream()
            .filter(
                p ->
                    p.verdict() != null
                        && p.verdict().verdict() == VerdictJsonCodec.VerifierVerdict.CONFIRMED)
            .count();
    long refuted =
        outcomes.values().stream()
            .filter(
                p ->
                    p.verdict() != null
                        && p.verdict().verdict() == VerdictJsonCodec.VerifierVerdict.REFUTED)
            .count();
    long insufficient =
        outcomes.values().stream()
            .filter(
                p ->
                    p.verdict() != null
                        && p.verdict().verdict()
                            == VerdictJsonCodec.VerifierVerdict.INSUFFICIENT_EVIDENCE)
            .count();
    trace.emit(
        "verify",
        Map.of(
            "file_batch",
            file,
            "findings",
            batch.size(),
            "verdicts",
            Map.of(
                "confirmed",
                confirmed,
                "refuted",
                refuted,
                "insufficient",
                insufficient,
                "unavailable",
                outcomes.size() - confirmed - refuted - insufficient)));
    return new BatchOutcome(outcomes, tokensIn, tokensOut);
  }

  /** 缩小上下文重试形态：只保留各锚行窗口（±60 行），丢弃全文与切片（02§4.2）。 */
  private VerificationBatchInput reduceContext(VerificationBatchInput input) {
    return new VerificationBatchInput(
        input.file(),
        input.items(),
        "（首轮上下文超限，已缩减为锚行窗口；请基于结论自带证据判定）",
        input.citedEvidence(),
        input.policySections(),
        input.followupContext());
  }

  private static Map<String, PerFinding> allUnavailable(List<FindingAggregator.Scored> batch) {
    Map<String, PerFinding> outcomes = new LinkedHashMap<>();
    for (FindingAggregator.Scored scored : batch) {
      outcomes.put(scored.finding().id(), new PerFinding(null, null, false, null));
    }
    return outcomes;
  }

  private static String findingText(CandidateFinding finding) {
    return finding.evidence() + "\n" + finding.description() + "\n" + finding.failureMode();
  }

  private static AgentRunner.Parse<VerdictJsonCodec.ParseResult> adapt(
      VerdictJsonCodec.ParseResult result) {
    return new AgentRunner.Parse<>(result, result.errors());
  }

  private static String briefSuffix(String file) {
    return String.format("%08x", file.hashCode()) + "-" + Math.abs(file.length());
  }
}
