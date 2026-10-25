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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.SubAgent;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
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
import org.cra.common.run.RunStatus;
import org.cra.context.api.ContextAssembler;
import org.cra.context.api.ContextAssembly;
import org.cra.context.api.ReviewPolicyParser;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.DepthPolicy;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PolicySections;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.cra.core.internal.pipeline.RunBudgetTracker;
import org.cra.core.internal.pipeline.SummarySections;
import org.cra.core.internal.pipeline.SummarySections.CoverageInput;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * M1 编排流水线（07 T1.3–T1.7）：幂等闸 → 策略/深度/分块规划 → 按 chunk 扇出评审（阶梯预算内） → 去重/锚定 → blame 复核 → 聚合 → publish。
 *
 * <ul>
 *   <li>第一道闸：commit 级幂等，只认账本中该 commit 的 DONE/DEGRADED 完成标记（02§4.1）；
 *   <li>第二道闸：指纹级去重 exact → ±5 滑窗容错 → possible_duplicate 入 trace（02§4.1，{@link
 *       FingerprintDeduper}）——发布中途掐断的 run 无完成标记，重跑按指纹补发缺失条目、已发布不重发（07§1 验收 3）；
 *   <li>上下文：L0 diff + L1 窗口/条件全文（04§1），REVIEW.md 一律读 base SHA（04§3.2）；
 *   <li>降档阶梯：≥70% 简化剩余 chunk 上下文、≥85% 发布集合截断 top-10、100% 终止剩余 chunk（02§4.4），截断丢弃数进
 *       stats/Summary（梯度沉默护栏）；
 *   <li>发布顺序：inline review → Summary issue comment（05§2.2）；锚定校验位置非法降级进 Summary（05§2.2）；正文 {@code
 *       <!--} 全量转义、标记行最后追加（05§1.3 规则 3）；
 *   <li>dry-run 全程零写（05§6）；run 级故障统一捕获落盘 FAILED（02§4.2）；trace 事件先行（07§6.5）。 *
 * </ul>
 *
 * <p>共享可变状态仅限 run 级预算账目（RunBudgetTracker）与构造期创建的固定线程池（09§6，daemon 线程）。
 */
public final class ReviewPipeline implements Orchestrator {

  private static final SplittableRandom RANDOM = new SplittableRandom();
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final String MARKER_VERSION = "2.1";
  private static final String REVIEW_MD = "REVIEW.md";
  private static final int MAX_PARALLEL_CHUNKS = 8;

  /** 过期收尾前缀的判定起点（02§5：[已被 run_xxx 取代]；已收尾的评论不再叠加前缀）。 */
  private static final String SUPERSEDED_PREFIX = "[已被 run_";

  /** 可疑账本标记的 Summary 告警阈值（05§1.3 规则 2：同一 PR ≥3 次疑似注入尝试）。 */
  private static final int LEDGER_SUSPECT_THRESHOLD = 3;

  private final GitPlatformAdapter adapter;
  private final ReviewLedger ledger;
  private final AgentRunner agentRunner;
  private final SubAgent<GeneralReviewInput> reviewer;
  private final Path runRoot;
  private final PipelineOptions options;
  private final LocalRepoContext localRepoContext;
  private final ContextAssembler contextAssembler;
  private final ReviewPolicyParser policyParser;
  private final ExecutorService chunkPool;

  public ReviewPipeline(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext) {
    this.adapter = adapter;
    this.ledger = ledger;
    this.agentRunner = new AgentRunner(gateway);
    this.reviewer = reviewer;
    this.runRoot = runRoot;
    this.options = options == null ? PipelineOptions.defaults() : options;
    this.localRepoContext = localRepoContext;
    this.contextAssembler = ContextAssembly.createContextAssembler();
    this.policyParser = ContextAssembly.createReviewPolicyParser();
    this.chunkPool =
        Executors.newFixedThreadPool(
            MAX_PARALLEL_CHUNKS,
            runnable -> {
              Thread thread = new Thread(runnable, "cra-chunk");
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

      DiffResult diff =
          adapter.fetchDiff(
              new RepoRef(request.repoOwner(), request.repoName()),
              request.baseSha(),
              request.headSha());

      // T1.4：REVIEW.md base 读取 + PR 内变更检测（04§3.2）
      ReviewPolicy policy = loadPolicy(trace, diff, request);
      boolean policyModified = hasReviewMdChange(diff);

      // T1.5/T1.6：深度档位 + 大 diff 降档 + 分 chunk（02§4.3/02§4.4/04§2.2）
      DepthPlan depthPlan = DepthPolicy.resolve(request.depth(), diff.totalPatchLines(), options);
      ChunkPlanner.Plan plan = ChunkPlanner.plan(diff.files(), policy, depthPlan.priorityOnly());
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

      RunOutcome outcome = executeChunks(request, runId, trace, diff, policy, depthPlan, plan);
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
                outcome.tokensIn(),
                outcome.tokensOut(),
                System.currentTimeMillis() - start,
                List.copyOf(outcome.degradations()),
                0,
                new Coverage(diff.files().size(), 0, "LLM 不可得，未评审")),
            "LLM 不可得: " + outcome.llmError());
      }
      List<String> degradations = new ArrayList<>(outcome.degradations());
      boolean degraded = false;
      if (!outcome.schemaErrors().isEmpty() && outcome.findings().isEmpty()) {
        // 候选全部被 schema 校验丢弃：run 降级收尾（仍发布完成标记，02§4.1 降级完成语义）
        degradations.add("schema-violation-all-dropped");
        degraded = true;
      }
      if (outcome.stoppedByBudget()) {
        degraded = true;
      }
      if (!"never".equals(options.publishUnverified())) {
        // 配置已受理但 M1 无验证层：如实声明不生效（05§5 review.publish-unverified）
        degradations.add("publish-unverified-labeled-not-effective-m1");
      }

      // 去重（指纹 exact → ±5 滑窗 → possible_duplicate，02§4.1）+ 锚定校验（05§2.2）
      List<FindingAggregator.Scored> candidates = new ArrayList<>();
      int skippedDuplicate = 0;
      Map<String, Optional<String>> contentCache = new HashMap<>();
      for (CandidateFinding finding : outcome.findings()) {
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

      // T1.7/T1.6：severity 排序 + nit 预算 + 阶梯截断 top-10（02§4.4）
      FindingAggregator.Aggregation aggregation =
          FindingAggregator.aggregate(
              candidates, policy, options.nitBudgetDefault(), outcome.truncationReached());
      trace.emit(
          "aggregate",
          Map.of(
              "candidates", candidates.size(),
              "ordered", aggregation.ordered().size(),
              "nit_dropped", aggregation.nitDropped(),
              "suppressed_by_truncation", aggregation.suppressedByTruncation()));

      // publish（dry-run 零写）；重试类事件入 stats 但不降级——降级语义 = 未按预期完整发布
      List<String> coverageLines =
          coverageLines(
              diff, policy, depthPlan, plan, outcome, blameOutcome, policyModified, aggregation);
      int publishedCount =
          publish(
              request,
              runId,
              trace,
              aggregation.ordered(),
              skippedDuplicate,
              aggregation.suppressedByTruncation(),
              degradations,
              degraded,
              suspectWarning,
              coverageLines);
      int fallbackCount =
          (int)
              aggregation.ordered().stream()
                  .filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK)
                  .count();
      int preExistingCount =
          (int)
              aggregation.ordered().stream()
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
              fallbackCount,
              preExistingCount,
              aggregation.suppressedByTruncation(),
              outcome.tokensIn(),
              outcome.tokensOut(),
              System.currentTimeMillis() - start,
              List.copyOf(degradations),
              (int)
                  aggregation.ordered().stream()
                      .filter(s -> s.finding().category() == FindingCategory.SECURITY)
                      .count(),
              new Coverage(diff.files().size(), filesReviewed, coverageNote(coverageLines)));
      String summaryLine =
          String.format(
              "run=%s status=%s depth=%s candidate=%d published=%d dedup=%d fallback=%d "
                  + "suppressed=%d tokens=%d/%d",
              runId,
              status,
              depthPlan.effectiveDepth().name().toLowerCase(Locale.ROOT),
              stats.findingsCandidate(),
              stats.findingsPublished(),
              stats.skippedDuplicate(),
              stats.anchorFallback(),
              stats.suppressedByTruncation(),
              stats.tokensIn(),
              stats.tokensOut());
      return finish(trace, runId, request, status, stats, summaryLine);
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

  // ---------- 阶段：分 chunk 扇出（T1.5/T1.6） ----------

  /** 全部 chunk 的执行产出。 */
  private record RunOutcome(
      List<CandidateFinding> findings,
      Map<String, FileContent> headFiles,
      long tokensIn,
      long tokensOut,
      boolean llmUnavailable,
      String llmError,
      List<String> schemaErrors,
      List<String> degradations,
      boolean stoppedByBudget,
      boolean truncationReached,
      List<String> contextNotes,
      List<String> trimDecisions) {}

  private RunOutcome executeChunks(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan) {
    Map<String, FileContent> headFiles = prefetchHeadFiles(request, plan);
    RunBudgetTracker budget = new RunBudgetTracker(options);
    String commitMessages =
        diff.commits().stream()
            .map(c -> c.sha() + " " + c.message())
            .collect(Collectors.joining("\n"));
    String prTitle = "PR #" + request.prNumber();
    String policySection = PolicySections.render(policy, hasReviewMdChange(diff));

    List<Future<ChunkOutcome>> futures = new ArrayList<>();
    List<ChunkOutcome> collected = new ArrayList<>();
    List<String> degradations = new ArrayList<>();
    List<String> contextNotes = new ArrayList<>();
    List<String> trimDecisions = new ArrayList<>();
    boolean stopped = false;
    boolean truncationReached = false;
    int dispatched = 0;
    try {
      while (true) {
        RunBudgetTracker.Phase phase = budget.phase();
        if (phase == RunBudgetTracker.Phase.STOP) {
          // 100%：终止剩余代理，已派发的照常收集（02§4.4 DEGRADED 收尾）
          if (dispatched < plan.chunks().size()) {
            degradations.add("budget-exhausted-stop");
            stopped = true;
            trace.emit(
                "budget_check",
                Map.of(
                    "phase",
                    "stop",
                    "ratio",
                    budget.ratio(),
                    "skipped_chunks",
                    plan.chunks().size() - dispatched));
          }
          if (futures.isEmpty()) {
            break;
          }
        } else {
          // 70%/85% 档：剩余 chunk 简化上下文；85% 档同时置发布截断标记
          boolean simplified = phase != RunBudgetTracker.Phase.NORMAL;
          if (phase == RunBudgetTracker.Phase.DEGRADE
              && !degradations.contains("budget-degrade-70")) {
            degradations.add("budget-degrade-70");
            trace.emit("budget_check", Map.of("phase", "degrade", "ratio", budget.ratio()));
          }
          if (phase == RunBudgetTracker.Phase.TRUNCATE) {
            truncationReached = true;
            if (!degradations.contains("budget-truncate-85")) {
              degradations.add("budget-truncate-85");
              trace.emit("budget_check", Map.of("phase", "truncate", "ratio", budget.ratio()));
            }
          }
          while (dispatched < plan.chunks().size() && futures.size() < MAX_PARALLEL_CHUNKS) {
            ChunkPlanner.Chunk chunk = plan.chunks().get(dispatched++);
            ContextInput contextInput = buildContextInput(chunk, headFiles, depthPlan, simplified);
            futures.add(
                chunkPool.submit(
                    () ->
                        runChunk(
                            request,
                            runId,
                            trace,
                            chunk,
                            prTitle,
                            commitMessages,
                            policySection,
                            contextInput)));
          }
          if (futures.isEmpty()) {
            break;
          }
        }
        ChunkOutcome outcome = futures.remove(0).get();
        budget.record(outcome.tokensIn(), outcome.tokensOut());
        collected.add(outcome);
        degradations.addAll(outcome.degradations());
        contextNotes.addAll(outcome.contextNotes());
        trimDecisions.addAll(outcome.trimDecisions());
        trace.emit(
            "chunk_done",
            Map.of(
                "chunk", outcome.chunkKey(),
                "files", outcome.fileCount(),
                "findings", outcome.findings().size(),
                "tokens_in", outcome.tokensIn(),
                "tokens_out", outcome.tokensOut(),
                "est_tokens", outcome.estimatedTokens()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("chunk 执行被中断", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(
          "chunk 执行失败: "
              + cause.getClass().getSimpleName()
              + ": "
              + cause.getMessage()
              + " @ "
              + (cause.getStackTrace().length > 0 ? cause.getStackTrace()[0] : "?"),
          cause);
    }

    List<CandidateFinding> findings = new ArrayList<>();
    long tokensIn = 0;
    long tokensOut = 0;
    boolean llmUnavailable = false;
    String llmError = null;
    List<String> schemaErrors = new ArrayList<>();
    for (ChunkOutcome outcome : collected) {
      findings.addAll(outcome.findings());
      tokensIn += outcome.tokensIn();
      tokensOut += outcome.tokensOut();
      if (outcome.llmUnavailable()) {
        llmUnavailable = true;
        llmError = outcome.llmError();
      }
      schemaErrors.addAll(outcome.schemaErrors());
    }
    // 终态复查：消耗只增不减，最后一批调用跨越 85%/100% 线时同样生效（02§4.4 按已消耗判定）
    RunBudgetTracker.Phase finalPhase = budget.phase();
    if (finalPhase.ordinal() >= RunBudgetTracker.Phase.TRUNCATE.ordinal()) {
      truncationReached = true;
      if (!degradations.contains("budget-truncate-85")) {
        degradations.add("budget-truncate-85");
        trace.emit("budget_check", Map.of("phase", "truncate-final", "ratio", budget.ratio()));
      }
    }
    return new RunOutcome(
        findings,
        headFiles,
        tokensIn,
        tokensOut,
        llmUnavailable,
        llmError,
        schemaErrors,
        degradations,
        stopped,
        truncationReached,
        contextNotes,
        trimDecisions);
  }

  /** 单 chunk 执行（LLM 调用；异常边界在 AgentRunner 与网关内转换）。 */
  private ChunkOutcome runChunk(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      ChunkPlanner.Chunk chunk,
      String prTitle,
      String commitMessages,
      String policySection,
      ContextInput contextInput) {
    AssembledContext context = contextAssembler.assemble(contextInput);
    GeneralReviewInput input =
        new GeneralReviewInput(
            prTitle, commitMessages, context.diffText(), context.windowText(), policySection);
    AgentBrief brief = reviewer.buildBrief(input);
    Path briefPath = trace.persistBrief(brief, chunkBriefSuffix(chunk));
    trace.emit(
        "agent_start",
        Map.of(
            "agent", reviewer.role().id(),
            "chunk", chunk.key(),
            "brief_path", briefPath.toString(),
            "est_tokens", context.estimatedTokens(),
            "trim_decisions", context.trimDecisions()));
    AgentRunner.AgentRun outcome = agentRunner.execute(brief, runId);
    trace.emit(
        "agent_output",
        Map.of(
            "agent", reviewer.role().id(),
            "chunk", chunk.key(),
            "findings", outcome.findings().size(),
            "tokens", Map.of("in", outcome.tokensIn(), "out", outcome.tokensOut()),
            "latency_ms", outcome.latencyMs()));
    return new ChunkOutcome(
        chunk.key(),
        chunk.files().size(),
        outcome.findings(),
        outcome.tokensIn(),
        outcome.tokensOut(),
        outcome.llmUnavailable(),
        outcome.llmError(),
        outcome.schemaErrors(),
        outcome.degradations(),
        context.coverageNotes(),
        context.trimDecisions(),
        context.estimatedTokens());
  }

  private record ChunkOutcome(
      String chunkKey,
      int fileCount,
      List<CandidateFinding> findings,
      long tokensIn,
      long tokensOut,
      boolean llmUnavailable,
      String llmError,
      List<String> schemaErrors,
      List<String> degradations,
      List<String> contextNotes,
      List<String> trimDecisions,
      long estimatedTokens) {}

  /** L1 预算 = 评审员输入预算 − L0 估算 − 定界/策略占用余量（04§1 预算裁决）；DEGRADE 档收窄窗口并关全文（02§4.4 70% 档的 M1 映射）。 */
  private ContextInput buildContextInput(
      ChunkPlanner.Chunk chunk,
      Map<String, FileContent> headFiles,
      DepthPlan depthPlan,
      boolean simplified) {
    boolean fullText = depthPlan.conditionalFullText() && !simplified;
    int window = simplified ? Math.min(depthPlan.windowLines(), 15) : depthPlan.windowLines();
    long reserved = TokenMath.estimateTokens(estimateChunkL0(chunk)) + 4_000;
    long l1Budget = Math.max(1_000, reviewer.budget().maxInputTokens() - reserved);
    return new ContextInput(
        chunk.files(), headFiles, window, fullText, depthPlan.fullFileMaxLines(), l1Budget);
  }

  private String estimateChunkL0(ChunkPlanner.Chunk chunk) {
    StringBuilder sb = new StringBuilder();
    for (DiffFile file : chunk.files()) {
      if (file.patch() != null) {
        sb.append(file.patch());
      }
    }
    return sb.toString();
  }

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

  // ---------- 阶段：聚合辅助 ----------

  private static String coverageNote(List<String> coverageLines) {
    return String.join("；", coverageLines);
  }

  private List<String> coverageLines(
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      RunOutcome outcome,
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
      List<String> coverageLines) {
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
                        inlineBody(s, runId, request.headSha()),
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
            coverageLines);
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
    recordToLedger(request, runId, ordered, result, skippedDuplicate, degraded);
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
      boolean degraded) {
    String status = degraded ? LedgerRunMarker.STATUS_DEGRADED : LedgerRunMarker.STATUS_DONE;
    long summaryCommentId = result.summaryCommentId().orElseThrow();
    ledger.recordRunCompletion(
        request.prNumber(),
        new LedgerRunMarker(
            runId, request.headSha(), status, ordered.size(), skippedDuplicate, summaryCommentId));
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

  private String inlineBody(FindingAggregator.Scored s, String runId, String commitSha) {
    CandidateFinding f = s.finding();
    StringBuilder body = new StringBuilder();
    body.append("**[")
        .append(f.severityProposed().name().toUpperCase(Locale.ROOT))
        .append("] ")
        .append(escape(f.title()))
        .append("** — `")
        .append(f.file())
        .append(":")
        .append(f.line())
        .append("`\n\n");
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
      List<String> coverageLines) {
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
      body.append(
          ordered.isEmpty() && skippedDuplicate == 0
              ? "本次未发现问题（评审返回空结果）。\n\n"
              : "本次无新发现（候选均被指纹去重抑制 " + skippedDuplicate + " 条）。\n\n");
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
    body.append("### 覆盖与统计\n");
    for (String line : coverageLines) {
      body.append("- ").append(line).append("\n");
    }
    body.append("- 指纹去重抑制 ")
        .append(skippedDuplicate)
        .append(" 条；位置非法降级 ")
        .append(fallbacks.size())
        .append(" 条\n");
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
        summaryLine);
  }

  private static ReviewStats emptyStats(long latencyMs) {
    return new ReviewStats(0, 0, 0, 0, 0, 0, 0, 0, latencyMs, List.of(), 0, new Coverage(0, 0, ""));
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
