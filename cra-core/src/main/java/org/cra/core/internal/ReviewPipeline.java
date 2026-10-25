package org.cra.core.internal;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.ReviewSuggestion;
import org.cra.common.run.RunStatus;
import org.cra.context.api.ContextAssembly;
import org.cra.context.api.ReviewPolicyParser;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.ConvergenceGuard;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.DepthPolicy;
import org.cra.core.internal.pipeline.ExactDedupMerger;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PolicySections;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.cra.core.internal.pipeline.PromotionEngine;
import org.cra.core.internal.pipeline.ReviewStage;
import org.cra.core.internal.pipeline.RunBudgetTracker;
import org.cra.core.internal.pipeline.SpotCheckFuse;
import org.cra.core.internal.pipeline.SummarySections;
import org.cra.core.internal.pipeline.SummarySections.CoverageInput;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * M2 编排流水线（07 T2.2/T2.3 评审段 + M1 存量）：幂等闸 → 策略/深度/分块规划 → 评审扇出（{@link ReviewStage}： 视角路由/并发
 * 8/失败隔离/精确合并） → 去重/锚定 → blame 复核 → 聚合 → publish。
 *
 * <ul>
 *   <li>第一道闸：commit 级幂等，只认账本中该 commit 的 DONE/DEGRADED 完成标记（02§4.1）；
 *   <li>第二道闸：指纹级去重 exact → ±5 滑窗容错 → possible_duplicate 入 trace（02§4.1，{@link
 *       FingerprintDeduper}）——发布中途掐断的 run 无完成标记，重跑按指纹补发缺失条目、已发布不重发（07§1 验收 3）；
 *   <li>上下文：L0 diff + L1 窗口/条件全文（04§1），REVIEW.md 一律读 base SHA（04§3.2）；
 *   <li>降档阶梯：≥70% 简化剩余 chunk 上下文并砍 1 视角、≥85% 发布集合截断 top-10、100% 终止剩余代理（02§4.4），截断丢弃数进
 *       stats/Summary（梯度沉默护栏）；
 *   <li>发布顺序：inline review → Summary issue comment（05§2.2）；锚定校验位置非法降级进 Summary（05§2.2）；正文 {@code
 *       <!--} 全量转义、标记行最后追加（05§1.3 规则 3）；
 *   <li>dry-run 全程零写（05§6）；run 级故障统一捕获落盘 FAILED（02§4.2）；trace 事件先行（07§6.5）。
 * </ul>
 *
 * <p>共享可变状态仅限 run 级预算账目（RunBudgetTracker，run 内单实例）；扇出线程池由 ReviewStage 持有（09§6）。
 */
public final class ReviewPipeline implements Orchestrator {

  private static final SplittableRandom RANDOM = new SplittableRandom();
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final String MARKER_VERSION = "2.1";
  private static final String REVIEW_MD = "REVIEW.md";
  private static final int MAX_PARALLEL_BATCHES = 4;

  /** 过期收尾前缀的判定起点（02§5：[已被 run_xxx 取代]；已收尾的评论不再叠加前缀）。 */
  private static final String SUPERSEDED_PREFIX = "[已被 run_";

  /** 可疑账本标记的 Summary 告警阈值（05§1.3 规则 2：同一 PR ≥3 次疑似注入尝试）。 */
  private static final int LEDGER_SUSPECT_THRESHOLD = 3;

  private final GitPlatformAdapter adapter;
  private final ReviewLedger ledger;
  private final ReviewStage reviewStage;
  private final AgentRunner agentRunner;
  private final SubAgent<VerificationBatchInput> verifier;
  private final SubAgent<JudgeCaseInput> judge;
  private final Path runRoot;
  private final PipelineOptions options;
  private final LocalRepoContext localRepoContext;
  private final ReviewPolicyParser policyParser;
  private final ExecutorService verifyPool;

  public ReviewPipeline(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext) {
    this.adapter = adapter;
    this.ledger = ledger;
    this.reviewStage =
        new ReviewStage(crew, new AgentRunner(gateway), ContextAssembly.createContextAssembler());
    this.agentRunner = new AgentRunner(gateway);
    this.verifier = verifier;
    this.judge = judge;
    this.runRoot = runRoot;
    this.options = options == null ? PipelineOptions.defaults() : options;
    this.localRepoContext = localRepoContext;
    this.policyParser = ContextAssembly.createReviewPolicyParser();
    this.verifyPool =
        Executors.newFixedThreadPool(
            MAX_PARALLEL_BATCHES,
            runnable -> {
              Thread thread = new Thread(runnable, "cra-verify");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public ReviewResult run(ReviewRequest request) {
    String runId = newRunId();
    TraceWriter trace = new TraceWriter(runRoot, runId);
    long start = System.currentTimeMillis();
    try {
      trace.emit(
          "run_start",
          Map.of(
              "repo", request.repoOwner() + "/" + request.repoName(),
              "pr", request.prNumber(),
              "base", request.baseSha(),
              "head", request.headSha(),
              "depth", request.depth().name().toLowerCase(Locale.ROOT),
              "dry_run", request.dryRun()));

      // 第一道闸：commit 级幂等（02§4.1，只认完整发布 DONE|DEGRADED；命中即全量跳过——Summary
      // 保持历史原样，避免 CI 重跑反复 PATCH 旧评论；更新语义如有需要随流水线线统一加）
      Optional<LedgerRunMarker> completion =
          ledger.findCompletion(request.prNumber(), request.headSha());
      trace.emit("gate_check", Map.of("gate", "commit", "hit", completion.isPresent()));
      if (completion.isPresent()) {
        return finish(
            trace,
            runId,
            request,
            RunStatus.SKIPPED,
            emptyStats(System.currentTimeMillis() - start),
            "commit "
                + shortSha(request.headSha())
                + " 已有完成标记（"
                + completion.get().status()
                + "），全量跳过");
      }

      // 第二道闸数据面：已发布指纹（补发缺失、已发布不重发，02§4.1）+ 账本解析分层（05§1.3 规则 5）
      List<LedgerFindingMarker> publishedMarkers = ledger.publishedFindings(request.prNumber());
      List<LedgerRunMarker> completedRuns = ledger.completedRuns(request.prNumber());
      int suspectCount = ledger.suspectMarkerCount(request.prNumber());
      boolean anyValidMarker = !publishedMarkers.isEmpty() || !completedRuns.isEmpty();
      trace.emit(
          "ledger_parse",
          Map.of(
              "valid",
              publishedMarkers.size() + completedRuns.size(),
              "suspect",
              suspectCount,
              "fallback",
              anyValidMarker ? "none" : "commit-gate"));
      // 规则 2（05§1.3）：可疑标记 ≥3 时 Summary 告警疑似注入尝试
      String suspectWarning =
          suspectCount >= LEDGER_SUSPECT_THRESHOLD
              ? "检测到 " + suspectCount + " 个可疑账本标记（LEDGER_SUSPECT），疑似注入尝试"
              : "";

      // T2.7：收敛决议（03§5）——轮次按账本完成标记计数；非首轮 diff-only 复审只评审增量
      ConvergenceGuard.RoundPlan roundPlan =
          ConvergenceGuard.resolve(completedRuns, request.headSha());
      trace.emit(
          "convergence",
          Map.of(
              "round",
              roundPlan.round(),
              "breached",
              roundPlan.breached(),
              "review_base",
              roundPlan.lastReviewedHead().map(ReviewPipeline::shortSha).orElse("pr-base")));

      DiffResult diff =
          adapter.fetchDiff(
              new RepoRef(request.repoOwner(), request.repoName()),
              request.baseSha(),
              request.headSha());
      List<String> earlyDegradations = new ArrayList<>();
      DiffResult reviewDiff =
          roundPlan
              .lastReviewedHead()
              .map(last -> incrementDiff(request, trace, last, diff, earlyDegradations))
              .orElse(diff);

      // T1.4：REVIEW.md base 读取 + PR 内变更检测（04§3.2；变更检测用全量 PR diff）
      ReviewPolicy policy = loadPolicy(trace, diff, request);
      boolean policyModified = hasReviewMdChange(diff);

      // T2.7 轮次超限（03§5 v1.3 语义强化）：三轮不收敛 = 设计问题——停止一切新 finding，
      // Summary 明确建议打回重新设计；不再执行评审与验证（人的职责是踩刹车）
      if (roundPlan.breached()) {
        List<String> breachCoverage =
            new ArrayList<>(List.of("第 " + roundPlan.round() + " 轮：验证-复审循环已超上限，本轮停止评审"));
        publish(
            request,
            runId,
            trace,
            List.of(),
            0,
            0,
            new ArrayList<>(List.of("convergence-round-limit")),
            false,
            suspectWarning,
            breachCoverage,
            ConvergenceGuard.breachAdvice(),
            Set.of(),
            0,
            Map.of());
        return finish(
            trace,
            runId,
            request,
            RunStatus.SUCCESS,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                System.currentTimeMillis() - start,
                List.of("convergence-round-limit"),
                0,
                new Coverage(diff.files().size(), 0, "轮次超限，停止评审")),
            "run=" + runId + " 轮次超限（第 " + roundPlan.round() + " 轮），停止评审并建议打回重新设计");
      }

      // T1.5/T1.6：深度档位 + 大 diff 降档 + 分 chunk（02§4.3/02§4.4/04§2.2；T2.7 diff-only：非首轮按增量）
      DepthPlan depthPlan =
          DepthPolicy.resolve(request.depth(), reviewDiff.totalPatchLines(), options);
      ChunkPlanner.Plan plan =
          ChunkPlanner.plan(reviewDiff.files(), policy, depthPlan.priorityOnly());
      trace.emit(
          "plan",
          Map.of(
              "effective_depth", depthPlan.effectiveDepth().name().toLowerCase(Locale.ROOT),
              "window_lines", depthPlan.windowLines(),
              "conditional_full_text", depthPlan.conditionalFullText(),
              "priority_only", depthPlan.priorityOnly(),
              "chunks", plan.chunks().size(),
              "skipped", plan.skippedPaths().size(),
              "notes", plan.notes()));

      // T2.2：评审扇出（视角路由/并发 8/失败隔离/精确合并）——run 级预算账目在 run() 持有，随派发记账
      RunBudgetTracker budget = new RunBudgetTracker(options);
      Map<String, FileContent> headFiles = prefetchHeadFiles(request, plan);
      String commitMessages =
          diff.commits().stream()
              .map(c -> c.sha() + " " + c.message())
              .collect(Collectors.joining("\n"));
      String prTitle = "PR #" + request.prNumber();
      String policySection = PolicySections.render(policy, hasReviewMdChange(diff));
      ReviewStage.StageOutcome outcome =
          reviewStage.execute(
              runId,
              trace,
              diff,
              policy,
              depthPlan,
              plan,
              headFiles,
              budget,
              prTitle,
              commitMessages,
              policySection);
      // T2.11：模板版本与 run 关联——trace 事件先行（05§1.4 template_versions；落库随发布成功）
      trace.emit("template_versions", Map.of("versions", outcome.templateVersions()));
      if (outcome.llmUnavailable()) {
        // LLM 不可得：run FAILED，不发布、无 DONE 标记——重跑重试（02§4.2）
        return finish(
            trace,
            runId,
            request,
            RunStatus.FAILED,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                outcome.tokensIn(),
                outcome.tokensOut(),
                System.currentTimeMillis() - start,
                List.copyOf(outcome.degradations()),
                0,
                new Coverage(diff.files().size(), 0, "LLM 不可得，未评审")),
            "LLM 不可得: " + outcome.llmError());
      }
      List<String> degradations = new ArrayList<>(outcome.degradations());
      degradations.addAll(earlyDegradations);
      boolean degraded = false;
      if (!outcome.schemaErrors().isEmpty() && outcome.findings().isEmpty()) {
        // 候选全部被 schema 校验丢弃：run 降级收尾（仍发布完成标记，02§4.1 降级完成语义）
        degradations.add("schema-violation-all-dropped");
        degraded = true;
      }
      if (outcome.stoppedByBudget()) {
        degraded = true;
      }
      if (outcome.degradations().contains("all-review-agents-failed")) {
        // 全代理执行失败（非 LLM 不可得）：零候选可信产出，降级收尾并声明（02§4.2 失败隔离语义）
        degraded = true;
      }

      // T2.8：run 内精确去重 merge（03§4.5：同 file ±3 行同 category；语义去重留 M3）
      ExactDedupMerger.Result merged = ExactDedupMerger.merge(outcome.findings());
      merged
          .mergedFrom()
          .forEach(
              (keptId, sources) ->
                  trace.emit("merge", Map.of("finding_id", keptId, "merged_from", sources)));
      trace.emit(
          "merge_summary",
          Map.of("clusters", merged.clusters(), "merged_away", merged.mergedAway()));

      // 去重（指纹 exact → ±5 滑窗 → possible_duplicate，02§4.1）+ 锚定校验（05§2.2）
      List<FindingAggregator.Scored> candidates = new ArrayList<>();
      int skippedDuplicate = 0;
      Map<String, Optional<String>> contentCache = new HashMap<>();
      for (CandidateFinding finding : merged.kept()) {
        List<String> lines =
            contentOf(finding.file(), request.headSha(), request, contentCache)
                .map(text -> List.of(text.split("\n", -1)))
                .orElse(List.of(""));
        FingerprintDeduper.Decision decision =
            FingerprintDeduper.evaluate(
                finding.file(),
                categoryOf(finding),
                finding.line(),
                lines,
                publishedMarkers,
                marker -> oldAnchorHashOf(marker, request, contentCache));
        String matchValue =
            switch (decision.match()) {
              case EXACT -> "exact";
              case SLIDING_WINDOW -> "sliding-window";
              case NONE -> decision.possibleDuplicate() ? "possible_duplicate" : "none";
            };
        trace.emit("dedup", Map.of("finding_id", finding.id(), "match", matchValue));
        if (decision.match() != FingerprintDeduper.Match.NONE) {
          skippedDuplicate++;
          continue;
        }
        AnchorStatus anchor = anchorStatus(diff, finding);
        trace.emit("anchor_check", Map.of("finding_id", finding.id(), "status", anchor.name()));
        candidates.add(
            new FindingAggregator.Scored(
                finding, decision.fingerprint(), anchor, PreExistingBlameChecker.Verdict.UNKNOWN));
      }

      // T1.7：pre-existing 嫌疑 jgit blame 复核（05§2；无本地仓库降级声明）
      PreExistingBlameChecker blameChecker = new PreExistingBlameChecker(localRepoContext);
      Set<String> prCommitShas =
          diff.commits().stream()
              .map(org.cra.common.git.CommitSummary::sha)
              .collect(Collectors.toSet());
      PreExistingBlameChecker.Outcome blameOutcome =
          blameChecker.check(
              candidates.stream().map(FindingAggregator.Scored::finding).toList(),
              prCommitShas,
              request.headSha());
      blameOutcome.notes().forEach(n -> trace.emit("blame_check", Map.of("note", n)));
      candidates =
          candidates.stream()
              .map(
                  s ->
                      new FindingAggregator.Scored(
                          s.finding(),
                          s.fingerprint(),
                          s.anchor(),
                          blameOutcome.verdictOf(s.finding().id())))
              .toList();

      // T2.4：验证波（文件批次 × balanced-review；INSUFFICIENT 受控补查；反锚定质控信号入 trace）
      VerificationWave wave =
          new VerificationWave(
              verifier,
              agentRunner,
              trace,
              budget,
              verifyPool,
              path -> contentOf(path, request.headSha(), request, contentCache),
              new CitedEvidenceResolver(localRepoContext));
      VerificationWave.WaveResult waveResult =
          wave.verify(candidates, policy, headFiles, reviewDiff, runId);
      degradations.addAll(waveResult.degradations());
      budget.record(waveResult.tokensIn(), waveResult.tokensOut());
      long tokensIn = outcome.tokensIn() + waveResult.tokensIn();
      long tokensOut = outcome.tokensOut() + waveResult.tokensOut();

      // T2.5/T2.6：晋升规则（M2 形态）+ Judge 仲裁与抽查复核（03§4.3/03§4.4）
      boolean publishUnverified = "labeled".equals(options.publishUnverified());
      JudgeArbiter arbiter = new JudgeArbiter(judge, agentRunner, trace);
      List<FindingAggregator.Scored> confirmedCandidates = new ArrayList<>();
      List<FindingAggregator.Scored> unverifiedLabeled = new ArrayList<>();
      int confirmedCount = 0;
      int rejectedCount = 0;
      int unverifiedCount = 0;
      for (FindingAggregator.Scored s : candidates) {
        CandidateFinding finding = s.finding();
        VerificationWave.PerFinding verifierOutcome = waveResult.outcomes().get(finding.id());
        VerdictJsonCodec.VerifierVerdict verdict =
            verifierOutcome == null || verifierOutcome.verdict() == null
                ? VerdictJsonCodec.VerifierVerdict.UNAVAILABLE
                : verifierOutcome.verdict().verdict();
        boolean fuseHit =
            SpotCheckFuse.hit(runId, finding.id(), options.importantRefutedSpotCheckPercent());
        PromotionEngine.Promotion promotion = PromotionEngine.promote(verdict, finding, fuseHit);
        trace.emit(
            "promotion",
            Map.of(
                "finding_id", finding.id(),
                "verifier_verdict", verdict.name(),
                "decision", promotion.decision().name().toLowerCase(Locale.ROOT),
                "reason", promotion.reason(),
                "spotcheck_fuse", fuseHit));
        switch (promotion.decision()) {
          case PUBLISH -> {
            confirmedCount++;
            confirmedCandidates.add(s);
          }
          case REJECT -> rejectedCount++;
          case UNVERIFIED -> {
            if (publishUnverified) {
              // 05§5 review.publish-unverified=labeled：带 UNVERIFIED 标签发布（02§4.2 可配项）
              unverifiedLabeled.add(s);
            } else {
              unverifiedCount++;
            }
          }
          case JUDGE -> {
            boolean spotCheck = "refuted-spotcheck".equals(promotion.reason());
            JudgeArbiter.Judgement judgement =
                arbitrate(arbiter, finding, verifierOutcome, spotCheck, headFiles, runId);
            budget.record(judgement.tokensIn(), judgement.tokensOut());
            tokensIn += judgement.tokensIn();
            tokensOut += judgement.tokensOut();
            if (judgement.available()
                && judgement.verdict() == JudgeJsonCodec.JudgeVerdict.CONFIRMED) {
              confirmedCount++;
              confirmedCandidates.add(s);
            } else if (judgement.available()) {
              rejectedCount++;
            } else if (spotCheck) {
              // 抽查复核的 judge 不可得：维持 REFUTED（保守不发布，02§4.2）
              rejectedCount++;
            } else {
              unverifiedCount++;
            }
          }
        }
      }

      // T1.7/T1.6：severity 排序 + nit 预算 + 阶梯截断 top-10（02§4.4；只对确认集执行）
      FindingAggregator.Aggregation aggregation =
          FindingAggregator.aggregate(
              confirmedCandidates, policy, options.nitBudgetDefault(), outcome.truncationReached());
      List<FindingAggregator.Scored> toPublish = new ArrayList<>(aggregation.ordered());
      toPublish.addAll(unverifiedLabeled);
      trace.emit(
          "aggregate",
          Map.of(
              "candidates", confirmedCandidates.size(),
              "ordered", aggregation.ordered().size(),
              "nit_dropped", aggregation.nitDropped(),
              "suppressed_by_truncation", aggregation.suppressedByTruncation()));

      // publish（dry-run 零写）；重试类事件入 stats 但不降级——降级语义 = 未按预期完整发布
      List<String> coverageLines =
          new ArrayList<>(
              coverageLines(
                  diff,
                  policy,
                  depthPlan,
                  plan,
                  outcome,
                  blameOutcome,
                  policyModified,
                  aggregation));
      if (roundPlan.round() > 1 && roundPlan.lastReviewedHead().isPresent()) {
        coverageLines.add(
            "diff-only 复审（第 "
                + roundPlan.round()
                + " 轮）：只评审自 "
                + shortSha(roundPlan.lastReviewedHead().get())
                + " 以来的增量，已评审文件未变更者不重审");
      }
      int publishedCount =
          publish(
              request,
              runId,
              trace,
              toPublish,
              skippedDuplicate,
              aggregation.suppressedByTruncation(),
              degradations,
              degraded,
              suspectWarning,
              coverageLines,
              null,
              unverifiedLabeled.stream().map(s -> s.finding().id()).collect(Collectors.toSet()),
              unverifiedCount,
              outcome.templateVersions());
      int fallbackCount =
          (int) toPublish.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).count();
      int preExistingCount =
          (int)
              toPublish.stream()
                  .filter(
                      s -> s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING)
                  .count();
      int filesReviewed = plan.chunks().stream().mapToInt(c -> c.files().size()).sum();

      RunStatus status = degraded ? RunStatus.DEGRADED : RunStatus.SUCCESS;
      ReviewStats stats =
          new ReviewStats(
              outcome.findings().size(),
              publishedCount,
              skippedDuplicate,
              confirmedCount,
              rejectedCount,
              unverifiedCount,
              fallbackCount,
              preExistingCount,
              aggregation.suppressedByTruncation(),
              tokensIn,
              tokensOut,
              System.currentTimeMillis() - start,
              List.copyOf(degradations),
              (int)
                  toPublish.stream()
                      .filter(s -> s.finding().category() == FindingCategory.SECURITY)
                      .count(),
              new Coverage(diff.files().size(), filesReviewed, coverageNote(coverageLines)));
      String summaryLine =
          String.format(
              "run=%s status=%s depth=%s candidate=%d confirmed=%d rejected=%d unverified=%d "
                  + "published=%d dedup=%d fallback=%d suppressed=%d tokens=%d/%d",
              runId,
              status,
              depthPlan.effectiveDepth().name().toLowerCase(Locale.ROOT),
              stats.findingsCandidate(),
              confirmedCount,
              rejectedCount,
              unverifiedCount,
              stats.findingsPublished(),
              stats.skippedDuplicate(),
              stats.anchorFallback(),
              stats.suppressedByTruncation(),
              stats.tokensIn(),
              stats.tokensOut());
      return finish(trace, runId, request, status, stats, summaryLine, suggestionsOf(toPublish));
    } catch (RuntimeException e) {
      // run 级不可恢复故障统一捕获：落盘 FAILED + trace，不阻塞 PR（02§4.2）
      return finish(
          trace,
          runId,
          request,
          RunStatus.FAILED,
          emptyStats(System.currentTimeMillis() - start),
          "run failed: " + e.getMessage());
    }
  }

  // ---------- 阶段：策略加载（T1.4） ----------

  /** REVIEW.md 一律从 base SHA 读取（04§3.2 规则 1）；读取失败（404）视为未配置；解析容错降级最小默认策略 + 警告（规则 4）。 */
  private ReviewPolicy loadPolicy(TraceWriter trace, DiffResult diff, ReviewRequest request) {
    String markdown;
    try {
      FileContent content =
          adapter.readFile(
              new RepoRef(request.repoOwner(), request.repoName()), request.baseSha(), REVIEW_MD);
      markdown = content.binary() ? null : content.text();
    } catch (CraIntegrationException e) {
      trace.emit("policy_load", Map.of("present", false, "reason", "read-failed"));
      return ReviewPolicy.empty();
    }
    ReviewPolicy policy = policyParser.parse(markdown);
    trace.emit(
        "policy_load",
        Map.of(
            "present", policy.present(),
            "modified_in_pr", hasReviewMdChange(diff),
            "warnings", policy.warnings()));
    return policy;
  }

  // ---------- 阶段：head 全文预取（ReviewStage 组装与工具作用域的数据面） ----------
  /** head 侧全文预取（cra-context 不发起平台调用，10§2）；不可读文件以 binary 占位（text=null）——指纹与 L1 都按不可得降级。 */
  private Map<String, FileContent> prefetchHeadFiles(
      ReviewRequest request, ChunkPlanner.Plan plan) {
    Map<String, FileContent> headFiles = new HashMap<>();
    RepoRef repo = new RepoRef(request.repoOwner(), request.repoName());
    for (ChunkPlanner.Chunk chunk : plan.chunks()) {
      for (DiffFile file : chunk.files()) {
        if (headFiles.containsKey(file.path()) || "removed".equals(file.status())) {
          continue;
        }
        try {
          headFiles.put(file.path(), adapter.readFile(repo, request.headSha(), file.path()));
        } catch (RuntimeException e) {
          headFiles.put(file.path(), new FileContent(file.path(), null, true));
        }
      }
    }
    return headFiles;
  }

  // ---------- 阶段：验证与晋升（T2.4–T2.6） ----------

  /** T2.7 diff-only 复审的增量 diff（03§5.2）：取自上一轮完成 head；取不到（force-push/接口失败）回退全量并声明。 */
  private DiffResult incrementDiff(
      ReviewRequest request,
      TraceWriter trace,
      String lastHead,
      DiffResult fallback,
      List<String> earlyDegradations) {
    try {
      return adapter.fetchDiff(
          new RepoRef(request.repoOwner(), request.repoName()), lastHead, request.headSha());
    } catch (RuntimeException e) {
      trace.emit(
          "convergence",
          Map.of(
              "note", "increment-diff-unavailable",
              "last_head", shortSha(lastHead),
              "error", String.valueOf(e.getMessage())));
      earlyDegradations.add("increment-diff-fallback-full");
      return fallback;
    }
  }

  /** Judge 案件组装 + 调用（03§4.4：brief = finding + 验证者判定与证据 + ≤3 文件上下文）。 */
  private JudgeArbiter.Judgement arbitrate(
      JudgeArbiter arbiter,
      CandidateFinding finding,
      VerificationWave.PerFinding verifierOutcome,
      boolean spotCheck,
      Map<String, FileContent> headFiles,
      String runId) {
    String verdictText =
        verifierOutcome != null && verifierOutcome.verdict() != null
            ? verifierOutcome.verdict().verdict().name()
                + "。理由："
                + verifierOutcome.verdict().rationale()
            : "（验证不可得：验证者超时/预算击穿/输出不合法）";
    JudgeCaseInput input =
        VerificationBatchAssembler.buildJudgeCase(
            finding, verdictText, JudgeArbiter.reasonText(spotCheck), headFiles);
    return arbiter.arbitrate(input, runId, finding.id());
  }

  // ---------- 阶段：聚合辅助 ----------

  private static String coverageNote(List<String> coverageLines) {
    return String.join("；", coverageLines);
  }

  private List<String> coverageLines(
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      ReviewStage.StageOutcome outcome,
      PreExistingBlameChecker.Outcome blameOutcome,
      boolean policyModified,
      FindingAggregator.Aggregation aggregation) {
    int filesReviewed = plan.chunks().stream().mapToInt(c -> c.files().size()).sum();
    return SummarySections.lines(
        new CoverageInput(
            depthPlan,
            diff.files().size(),
            filesReviewed,
            plan.skippedPaths(),
            outcome.contextNotes(),
            outcome.trimDecisions(),
            plan.notes(),
            PolicySections.warnings(policy, policyModified),
            blameOutcome.notes(),
            aggregation.nitDropped(),
            aggregation.suppressedByTruncation()));
  }

  // ---------- 阶段：publish ----------

  private int publish(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      List<FindingAggregator.Scored> ordered,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      String suspectWarning,
      List<String> coverageLines,
      String convergenceAdvice,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      Map<String, String> templateVersions) {
    List<FindingAggregator.Scored> fallbacks =
        ordered.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).toList();
    List<InlineComment> inline =
        ordered.stream()
            .filter(s -> s.anchor() == AnchorStatus.INLINE)
            .map(
                s ->
                    new InlineComment(
                        s.finding().file(),
                        s.finding().line(),
                        s.finding().side(),
                        inlineBody(s, runId, request.headSha(), unverifiedIds),
                        s.finding().id()))
            .toList();
    String summaryBody =
        summaryBody(
            request,
            runId,
            ordered,
            fallbacks,
            skippedDuplicate,
            suppressedByTruncation,
            degradations,
            degraded,
            suspectWarning,
            coverageLines,
            convergenceAdvice,
            unverifiedIds,
            unverifiedUnpublished);
    if (request.dryRun()) {
      // dry-run：全程零写（05§6），发布器不触达 adapter 与账本写侧
      trace.emit(
          "publish",
          Map.of(
              "dry_run",
              true,
              "comments",
              inline.size(),
              "skipped_duplicate",
              skippedDuplicate,
              "suppressed_by_truncation",
              suppressedByTruncation,
              "done_marker",
              false));
      return ordered.size();
    }
    String reviewBody =
        inline.isEmpty()
            ? "cra 评审（run_" + runId + "）：无可 inline 的发现，详见本 PR 的评审 Summary 评论。"
            : "cra 评审（run_"
                + runId
                + "）：发现 "
                + inline.size()
                + " 条问题（inline），详见本 PR 的评审 Summary 评论。";
    PostReviewCommand cmd =
        new PostReviewCommand(
            request.repoOwner(),
            request.repoName(),
            request.prNumber(),
            request.headSha(),
            reviewBody,
            inline,
            summaryBody);
    PrReviewResult result = adapter.postReview(cmd);
    if (result.items().stream().anyMatch(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
        && !inline.isEmpty()) {
      // 整包 422 逐条降级产生了额外 SUMMARY_FALLBACK（05§2.2），run 降级记录
      degradations.add("422-per-item-fallback");
    }
    long publishedActual =
        result.items().stream().filter(i -> i.publishStatus() == PublishStatus.PUBLISHED).count();
    long fallbackActual =
        result.items().stream()
            .filter(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
            .count();
    trace.emit(
        "publish",
        Map.of(
            "comments",
            result.items().size(),
            "skipped_duplicate",
            skippedDuplicate,
            "suppressed_by_truncation",
            suppressedByTruncation,
            "done_marker",
            result.summaryCommentId().isPresent()));
    if (result.summaryCommentId().isEmpty()) {
      // Summary 发布失败 = 无完成标记 = 不完整 run：不回写账本、不做过期收尾，重跑走指纹级补发（02§4.1）
      return (int) (publishedActual + fallbackActual);
    }
    recordToLedger(request, runId, ordered, result, skippedDuplicate, degraded, templateVersions);
    int supersedeFailures = supersedePreviousSummaries(request, runId, trace);
    if (supersedeFailures > 0) {
      degradations.add("summary-supersede-failed:x" + supersedeFailures);
    }
    return (int) (publishedActual + fallbackActual);
  }

  // ---------- 账本写侧与过期收尾（02§5） ----------

  /**
   * 发布成功后账本化登记：完成标记 + 全部应发 finding（含 Summary 降级条目 commentId=0）。GitHub 形态 下为无操作（标记已内嵌评论）；SQLite
   * 形态由此获得幂等闸与补发的事实源（02§4.1/05§1.5）。
   */
  private void recordToLedger(
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      PrReviewResult result,
      int skippedDuplicate,
      boolean degraded,
      Map<String, String> templateVersions) {
    String status = degraded ? LedgerRunMarker.STATUS_DEGRADED : LedgerRunMarker.STATUS_DONE;
    long summaryCommentId = result.summaryCommentId().orElseThrow();
    ledger.recordRunCompletion(
        request.prNumber(),
        new LedgerRunMarker(
            runId, request.headSha(), status, ordered.size(), skippedDuplicate, summaryCommentId));
    // T2.11：模板版本随 run 落库（05§1.5 prompt_template_version；SQLite 形态覆写，GitHub 形态 default 无操作）
    ledger.recordTemplateVersions(request.prNumber(), runId, templateVersions);
    Map<String, Long> commentIds =
        result.items().stream()
            .filter(i -> i.commentId() != null)
            .collect(
                Collectors.toMap(
                    ReviewItemResult::findingId, ReviewItemResult::commentId, (a, b) -> a));
    for (FindingAggregator.Scored s : ordered) {
      ledger.recordPublishedFinding(
          request.prNumber(),
          new LedgerFindingMarker(
              s.fingerprint(),
              s.finding().file(),
              s.finding().line(),
              categoryOf(s.finding()),
              s.finding().id(),
              runId,
              request.headSha(),
              commentIds.getOrDefault(s.finding().id(), 0L)));
    }
  }

  /**
   * 过期收尾（02§5）：新 run 发布成功后，对同 PR 旧 head_sha 的 bot Summary issue comment 加前缀 {@code [已被 run_xxx
   * 取代]}（PATCH 端点，inline 评论不删——GitHub 自动标 outdated，处置采集依赖其在）。 幂等：已带取代前缀的不再叠加；body 不可得（SQLite 形态）或
   * PATCH 失败只入 trace/degradation， 不回滚完成标记（run 已完整发布）。
   *
   * @return PATCH 失败次数
   */
  private int supersedePreviousSummaries(ReviewRequest request, String runId, TraceWriter trace) {
    int failures = 0;
    for (LedgerRunMarker old : ledger.completedRuns(request.prNumber())) {
      if (old.commitSha().equalsIgnoreCase(request.headSha()) || old.commentId() <= 0) {
        continue;
      }
      String body =
          ledger.comment(request.prNumber(), old.commentId()).map(LedgerComment::body).orElse(null);
      if (body == null) {
        trace.emit(
            "summary_supersede", Map.of("comment_id", old.commentId(), "status", "unavailable"));
        continue;
      }
      if (body.startsWith(SUPERSEDED_PREFIX)) {
        trace.emit("summary_supersede", Map.of("comment_id", old.commentId(), "status", "skipped"));
        continue;
      }
      try {
        adapter.updateSummaryComment(
            new RepoRef(request.repoOwner(), request.repoName()),
            request.prNumber(),
            old.commentId(),
            "[已被 " + runId + " 取代]\n\n" + body);
        trace.emit(
            "summary_supersede",
            Map.of("comment_id", old.commentId(), "status", "patched", "superseded_by", runId));
      } catch (RuntimeException e) {
        // 收尾失败不回滚完成标记（降级写状态，09§4）；次数计入 stats 供 Summary/健康度可见
        trace.emit(
            "summary_supersede",
            Map.of(
                "comment_id",
                old.commentId(),
                "status",
                "failed",
                "error",
                String.valueOf(e.getMessage())));
        failures++;
      }
    }
    return failures;
  }

  // ---------- 组装：inline 评论与 Summary 正文 ----------

  private String inlineBody(
      FindingAggregator.Scored s, String runId, String commitSha, Set<String> unverifiedIds) {
    CandidateFinding f = s.finding();
    boolean unverified = unverifiedIds.contains(f.id());
    StringBuilder body = new StringBuilder();
    body.append(unverified ? "**[UNVERIFIED][" : "**[")
        .append(f.severityProposed().name().toUpperCase(Locale.ROOT))
        .append("] ")
        .append(escape(f.title()))
        .append("** — `")
        .append(f.file())
        .append(":")
        .append(f.line())
        .append("`\n\n");
    if (unverified) {
      body.append("> ⚠️ 验证不可得（复核者超时/预算击穿），本条未经独立复核即发布（publish-unverified=labeled）。\n\n");
    }
    body.append(escape(f.description())).append("\n\n");
    body.append("**失败模式**：").append(escape(f.failureMode())).append("\n\n");
    body.append("**证据**：\n```\n").append(f.evidence()).append("\n```\n");
    if (f.suggestion() != null && !f.suggestion().isBlank()) {
      body.append("\n**建议**：\n```\n").append(f.suggestion()).append("\n```\n");
    }
    // 规则 3（05§1.3）：标记行由发布器在全部正文之后追加（正文已全量转义 <!--）
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | fp=")
        .append(s.fingerprint())
        .append(" | file=")
        .append(f.file())
        .append(" | cat=")
        .append(categoryOf(f))
        .append(" | id=")
        .append(f.id())
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(commitSha)
        .append(" | line=")
        .append(f.line())
        .append(" -->");
    return body.toString();
  }

  private String summaryBody(
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      List<FindingAggregator.Scored> fallbacks,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      String suspectWarning,
      List<String> coverageLines,
      String convergenceAdvice,
      Set<String> unverifiedIds,
      int unverifiedUnpublished) {
    StringBuilder body = new StringBuilder();
    body.append("## cra 评审 Summary（").append(runId).append("）\n\n");
    body.append("变更：PR #")
        .append(request.prNumber())
        .append(" ")
        .append(shortSha(request.baseSha()))
        .append("..")
        .append(shortSha(request.headSha()))
        .append("\n\n");
    if (!suspectWarning.isEmpty()) {
      // 05§1.3 规则 2：可疑标记 ≥3 → Summary 警告疑似注入尝试
      body.append("> ⚠️ ").append(suspectWarning).append("\n\n");
    }
    List<FindingAggregator.Scored> important =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.IMPORTANT).toList();
    List<FindingAggregator.Scored> nits =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.NIT).toList();
    List<FindingAggregator.Scored> preExisting =
        ordered.stream()
            .filter(s -> s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING)
            .toList();
    if (important.isEmpty() && nits.isEmpty() && fallbacks.isEmpty() && preExisting.isEmpty()) {
      if (ordered.isEmpty() && skippedDuplicate == 0) {
        // 02§4.2：验证不可得 ≠ 无问题——计数声明，不把 UNVERIFIED 静默当空结果
        body.append(
            unverifiedUnpublished > 0
                ? "本次无已确认发现；" + unverifiedUnpublished + " 条候选验证不可得（UNVERIFIED，默认不发布）。\n\n"
                : "本次未发现问题（评审返回空结果）。\n\n");
      } else {
        body.append("本次无新发现（候选均被指纹去重抑制 ").append(skippedDuplicate).append(" 条）。\n\n");
      }
    } else {
      if (!important.isEmpty()) {
        body.append("### Important\n");
        for (FindingAggregator.Scored s : important) {
          body.append(listLine(s));
        }
        body.append("\n");
      }
      if (!nits.isEmpty()) {
        body.append("### Nit\n");
        for (FindingAggregator.Scored s : nits) {
          body.append(listLine(s));
        }
        body.append("\n");
      }
      if (!preExisting.isEmpty()) {
        body.append("### Pre-existing（jgit blame 确证变更前已存在）\n");
        for (FindingAggregator.Scored s : preExisting) {
          body.append(listLine(s));
        }
        body.append("\n");
      }
      if (!fallbacks.isEmpty()) {
        body.append("### 位置非法（降级进 Summary）\n");
        for (FindingAggregator.Scored s : fallbacks) {
          body.append("- **")
              .append(escape(s.finding().title()))
              .append("** — `")
              .append(s.finding().file())
              .append(":")
              .append(s.finding().line())
              .append("`：")
              .append(escape(s.finding().description()))
              .append("\n");
        }
        body.append("\n");
      }
    }
    if (convergenceAdvice != null && !convergenceAdvice.isBlank()) {
      // T2.7 轮次超限（03§5）：停止一切新 finding，建议打回重新设计——人在这一关踩刹车
      body.append("### 收敛控制\n> ⚠️ ").append(convergenceAdvice).append("\n\n");
    }
    body.append("### 覆盖与统计\n");
    for (String line : coverageLines) {
      body.append("- ").append(line).append("\n");
    }
    body.append("- 指纹去重抑制 ")
        .append(skippedDuplicate)
        .append(" 条；位置非法降级 ")
        .append(fallbacks.size())
        .append(" 条\n");
    body.append("- 复核与晋升：确认 ")
        .append(ordered.size() - unverifiedIds.size())
        .append(" 条")
        .append(
            unverifiedIds.isEmpty()
                ? ""
                : "（含 UNVERIFIED 标签发布 " + unverifiedIds.size() + " 条——验证不可得）")
        .append(
            unverifiedUnpublished > 0
                ? "；验证不可得未发布 " + unverifiedUnpublished + " 条（UNVERIFIED，02§4.2）"
                : "")
        .append("\n");
    if (!degradations.isEmpty()) {
      body.append("- 降级事件：").append(String.join("、", degradations)).append("\n");
    }
    // run 完成标记（05§1.3）：DEGRADED 也是完整发布（02§4.1）；suppressed = 指纹抑制计数
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(request.headSha())
        .append(" | status=")
        .append(degraded ? "DEGRADED" : "DONE")
        .append(" | findings=")
        .append(ordered.size())
        .append(" | suppressed=")
        .append(suppressedByTruncation)
        .append(" -->");
    return body.toString();
  }

  private static String listLine(FindingAggregator.Scored s) {
    return "- **"
        + escape(s.finding().title())
        + "** — `"
        + s.finding().file()
        + ":"
        + s.finding().line()
        + "`\n";
  }

  // ---------- 去重与锚定 ----------

  /**
   * 文件内容缓存读取（新代码与旧代码共用，key = path|sha）：二进制/不可得为 empty——空内容锚仍确定性， 该 finding 走 possible_duplicate
   * 分支而非崩溃（09§4 降级写状态）。
   */
  private Optional<String> contentOf(
      String path, String sha, ReviewRequest request, Map<String, Optional<String>> cache) {
    return cache.computeIfAbsent(
        path + "|" + sha,
        k -> {
          try {
            FileContent file =
                adapter.readFile(new RepoRef(request.repoOwner(), request.repoName()), sha, path);
            return file.binary() || file.text() == null
                ? Optional.empty()
                : Optional.of(file.text());
          } catch (RuntimeException e) {
            return Optional.empty();
          }
        });
  }

  /**
   * 账本标记的发布时锚行内容哈希（按标记 commit_sha 取旧文件内容重算——滑窗匹配键，02§4.1 M1 实现口径）。 旧内容不可得（force-push/删除）时为
   * empty：该标记不参与滑窗，仅保留 exact 与 possible_duplicate。
   */
  private Optional<String> oldAnchorHashOf(
      LedgerFindingMarker marker, ReviewRequest request, Map<String, Optional<String>> cache) {
    return contentOf(marker.file(), marker.commitSha(), request, cache)
        .map(
            text ->
                FingerprintCalculator.sha256(
                    FingerprintDeduper.lineAt(List.of(text.split("\n", -1)), marker.line())
                        .strip()));
  }

  private static AnchorStatus anchorStatus(DiffResult diff, CandidateFinding finding) {
    if (!Side.RIGHT.equals(finding.side())) {
      return AnchorStatus.SUMMARY_FALLBACK;
    }
    Optional<DiffFile> file =
        diff.files().stream().filter(f -> f.path().equals(finding.file())).findFirst();
    return file.map(
            f ->
                f.containsLine(Side.RIGHT, finding.line())
                    ? AnchorStatus.INLINE
                    : AnchorStatus.SUMMARY_FALLBACK)
        .orElse(AnchorStatus.SUMMARY_FALLBACK);
  }

  // ---------- 公共 ----------

  private ReviewResult finish(
      TraceWriter trace,
      String runId,
      ReviewRequest request,
      RunStatus status,
      ReviewStats stats,
      String summaryLine) {
    return finish(trace, runId, request, status, stats, summaryLine, List.of());
  }

  /** 聚合后建议快照（本地 pre-PR 报告的建议清单数据源，02§5 本地形态；CI 形态不消费）。 */
  private static List<ReviewSuggestion> suggestionsOf(List<FindingAggregator.Scored> ordered) {
    return ordered.stream()
        .map(
            s ->
                new ReviewSuggestion(
                    s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING
                        ? "PRE-EXISTING"
                        : s.finding().severityProposed().name(),
                    s.finding().title(),
                    s.finding().file(),
                    s.finding().line(),
                    s.finding().description(),
                    s.finding().suggestion() == null ? "" : s.finding().suggestion()))
        .toList();
  }

  private ReviewResult finish(
      TraceWriter trace,
      String runId,
      ReviewRequest request,
      RunStatus status,
      ReviewStats stats,
      String summaryLine,
      List<ReviewSuggestion> suggestions) {
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("status", status.name().toLowerCase(Locale.ROOT));
    end.put("findings_published", stats.findingsPublished());
    end.put("skipped_duplicate", stats.skippedDuplicate());
    end.put("suppressed_by_truncation", stats.suppressedByTruncation());
    end.put("tokens_in", stats.tokensIn());
    end.put("tokens_out", stats.tokensOut());
    end.put("latency_ms", stats.latencyMs());
    trace.emit("run_end", end);
    return new ReviewResult(
        runId,
        request.repoOwner(),
        request.repoName(),
        request.prNumber(),
        request.headSha(),
        status,
        stats,
        "file://" + trace.runDir(),
        summaryLine,
        suggestions);
  }

  private static ReviewStats emptyStats(long latencyMs) {
    return new ReviewStats(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, latencyMs, List.of(), 0, new Coverage(0, 0, ""));
  }

  private static boolean hasReviewMdChange(DiffResult diff) {
    return diff.files().stream().anyMatch(f -> REVIEW_MD.equals(f.path()));
  }

  private static String categoryOf(CandidateFinding finding) {
    return finding.category().name().toLowerCase(Locale.ROOT);
  }

  private static String escape(String text) {
    // 05§1.3 规则 3：正文 <!-- → &lt;!--（正文永远不可能再产生未转义 HTML 注释）
    return text.replace("<!--", "&lt;!--");
  }

  private static String shortSha(String sha) {
    return sha.substring(0, Math.min(7, sha.length()));
  }

  private static String newRunId() {
    return "run_"
        + LocalDateTime.now().format(TS)
        + "-"
        + String.format("%06x", RANDOM.nextInt(1 << 24));
  }

  private static String chunkBriefSuffix(ChunkPlanner.Chunk chunk) {
    return "c" + (Math.abs(chunk.key().hashCode()) % 10000) + "-" + chunk.files().size();
  }
}
