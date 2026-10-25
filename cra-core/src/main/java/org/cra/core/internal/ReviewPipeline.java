package org.cra.core.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.context.api.ContextAssembly;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.ConvergenceGuard;
import org.cra.core.internal.pipeline.CrossCheckHistory;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.DepthPolicy;
import org.cra.core.internal.pipeline.ExactDedupMerger;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PolicySections;
import org.cra.core.internal.pipeline.ReviewStage;
import org.cra.core.internal.pipeline.RunBudgetTracker;
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
 * <p>协作边界：数据面见 {@link PipelineDataLoader}，门禁见 {@link FindingGatekeeper}，验证晋升见 {@link
 * FindingPromoter}， 发布/账本见 {@link ReviewPublisher}，正文渲染见 {@link ReviewBodyRenderer}，覆盖声明见 {@link
 * CoverageReportBuilder}，收尾见 {@link ReviewRunFinisher}。本类只保留阶段编排。
 *
 * <p>共享可变状态仅限 run 级预算账目（RunBudgetTracker，run 内单实例）；扇出线程池由 ReviewStage 持有（09§6）。
 */
public final class ReviewPipeline implements Orchestrator {

  private static final int MAX_PARALLEL_BATCHES = 4;

  /** 可疑账本标记的 Summary 告警阈值（05§1.3 规则 2：同一 PR ≥3 次疑似注入尝试）。 */
  private static final int LEDGER_SUSPECT_THRESHOLD = 3;

  private final ReviewLedger ledger;
  private final ReviewStage reviewStage;
  private final AgentRunner agentRunner;
  private final SubAgent<VerificationBatchInput> verifier;
  private final Path runRoot;
  private final PipelineOptions options;
  private final PipelineDataLoader dataLoader;
  private final FindingGatekeeper gatekeeper;
  private final FindingPromoter promoter;
  private final ReviewPublisher publisher;
  private final CitedEvidenceResolver citedResolver;
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
    this(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options,
        localRepoContext,
        CrossCheckerOptions.disabled());
  }

  /**
   * @param crossCheckerOptions CrossChecker 触发配置（05§5 agents.crosschecker；默认关闭不改变基线算术，04§2.2）
   */
  public ReviewPipeline(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions) {
    this.ledger = ledger;
    this.runRoot = runRoot;
    this.options = options == null ? PipelineOptions.defaults() : options;
    this.verifier = verifier;
    this.agentRunner = new AgentRunner(gateway);
    CrossCheckerOptions effectiveCrossChecker =
        crossCheckerOptions == null ? CrossCheckerOptions.disabled() : crossCheckerOptions;
    this.reviewStage =
        new ReviewStage(
            crew, agentRunner, ContextAssembly.createContextAssembler(), effectiveCrossChecker);
    this.dataLoader =
        new PipelineDataLoader(
            adapter,
            ContextAssembly.createReviewPolicyParser(),
            localRepoContext,
            effectiveCrossChecker);
    this.gatekeeper = new FindingGatekeeper(dataLoader, localRepoContext);
    this.citedResolver = new CitedEvidenceResolver(localRepoContext);
    this.promoter = new FindingPromoter(judge, agentRunner, this.options, citedResolver);
    this.publisher = new ReviewPublisher(adapter, ledger);
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
    String runId = ReviewRunFinisher.newRunId();
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
        return ReviewRunFinisher.finish(
            trace,
            runId,
            request,
            RunStatus.SKIPPED,
            ReviewRunFinisher.emptyStats(System.currentTimeMillis() - start),
            "commit "
                + ReviewBodyRenderer.shortSha(request.headSha())
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
              roundPlan.lastReviewedHead().map(ReviewBodyRenderer::shortSha).orElse("pr-base")));

      DiffResult diff = dataLoader.fetchDiff(request);
      List<String> earlyDegradations = new ArrayList<>();
      DiffResult reviewDiff =
          roundPlan
              .lastReviewedHead()
              .map(last -> dataLoader.incrementDiff(request, trace, last, diff, earlyDegradations))
              .orElse(diff);

      // T1.4：REVIEW.md base 读取 + PR 内变更检测（04§3.2；变更检测用全量 PR diff）
      ReviewPolicy policy = dataLoader.loadPolicy(trace, diff, request);
      boolean policyModified = PipelineDataLoader.hasReviewMdChange(diff);

      // T2.7 轮次超限（03§5 v1.3 语义强化）：三轮不收敛 = 设计问题——停止一切新 finding，
      // Summary 明确建议打回重新设计；不再执行评审与验证（人的职责是踩刹车）
      if (roundPlan.breached()) {
        List<String> breachCoverage =
            new ArrayList<>(List.of("第 " + roundPlan.round() + " 轮：验证-复审循环已超上限，本轮停止评审"));
        publisher.publish(
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
        return ReviewRunFinisher.finish(
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
      Map<String, FileContent> headFiles = dataLoader.prefetchHeadFiles(request, plan);
      String commitMessages =
          diff.commits().stream()
              .map(c -> c.sha() + " " + c.message())
              .collect(Collectors.joining("\n"));
      String prTitle = "PR #" + request.prNumber();
      String policySection =
          PolicySections.render(policy, PipelineDataLoader.hasReviewMdChange(diff));
      // M4f-lite：CrossChecker 变更史（03§3.1 输入 b）——仅在配置开启且非 quick 档时查本地仓库
      CrossCheckHistory crossCheckHistory =
          dataLoader.resolveCrossCheckHistory(request, diff, plan, depthPlan);
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
              policySection,
              crossCheckHistory);
      // T2.11：模板版本与 run 关联——trace 事件先行（05§1.4 template_versions；落库随发布成功）
      trace.emit("template_versions", Map.of("versions", outcome.templateVersions()));
      if (outcome.llmUnavailable()) {
        // LLM 不可得：run FAILED，不发布、无 DONE 标记——重跑重试（02§4.2）
        return ReviewRunFinisher.finish(
            trace,
            runId,
            request,
            RunStatus.FAILED,
            ReviewRunFinisher.llmUnavailableStats(
                outcome.tokensIn(),
                outcome.tokensOut(),
                System.currentTimeMillis() - start,
                List.copyOf(outcome.degradations()),
                diff.files().size()),
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

      // 去重（指纹 exact → ±5 滑窗 → possible_duplicate，02§4.1）+ 锚定校验（05§2.2）+ blame 复核
      Map<String, Optional<String>> contentCache = new HashMap<>();
      FindingGatekeeper.Outcome gateOutcome =
          gatekeeper.gate(request, diff, merged.kept(), publishedMarkers, contentCache, trace);
      List<FindingAggregator.Scored> candidates = gateOutcome.candidates();
      int skippedDuplicate = gateOutcome.skippedDuplicate();

      // T2.4：验证波（文件批次 × balanced-review；INSUFFICIENT 受控补查；反锚定质控信号入 trace）
      VerificationWave wave =
          new VerificationWave(
              verifier,
              agentRunner,
              trace,
              budget,
              verifyPool,
              path -> dataLoader.contentOf(path, request.headSha(), request, contentCache),
              citedResolver);
      VerificationWave.WaveResult waveResult =
          wave.verify(candidates, policy, headFiles, reviewDiff, runId);
      degradations.addAll(waveResult.degradations());
      budget.record(waveResult.tokensIn(), waveResult.tokensOut());
      long tokensIn = outcome.tokensIn() + waveResult.tokensIn();
      long tokensOut = outcome.tokensOut() + waveResult.tokensOut();

      // T2.5/T2.6：晋升规则（M2 形态）+ Judge 仲裁与抽查复核（03§4.3/03§4.4）
      FindingPromoter.Outcome promotion =
          promoter.promote(runId, candidates, waveResult, headFiles, policy, budget, trace);
      tokensIn += promotion.tokensIn();
      tokensOut += promotion.tokensOut();
      List<FindingAggregator.Scored> confirmedCandidates = promotion.confirmed();
      List<FindingAggregator.Scored> unverifiedLabeled = promotion.unverifiedLabeled();
      int confirmedCount = promotion.confirmedCount();
      int rejectedCount = promotion.rejectedCount();
      int unverifiedCount = promotion.unverifiedCount();

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
              CoverageReportBuilder.lines(
                  diff,
                  policy,
                  depthPlan,
                  plan,
                  outcome,
                  gateOutcome.blameOutcome(),
                  policyModified,
                  aggregation));
      if (roundPlan.round() > 1 && roundPlan.lastReviewedHead().isPresent()) {
        coverageLines.add(
            "diff-only 复审（第 "
                + roundPlan.round()
                + " 轮）：只评审自 "
                + ReviewBodyRenderer.shortSha(roundPlan.lastReviewedHead().get())
                + " 以来的增量，已评审文件未变更者不重审");
      }
      int publishedCount =
          publisher.publish(
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
      ReviewRunFinisher.PublishCounters counters = ReviewRunFinisher.counters(toPublish, plan);
      RunStatus status = degraded ? RunStatus.DEGRADED : RunStatus.SUCCESS;
      ReviewStats stats =
          ReviewRunFinisher.stats(
              outcome.findings().size(),
              publishedCount,
              skippedDuplicate,
              confirmedCount,
              rejectedCount,
              unverifiedCount,
              counters,
              aggregation.suppressedByTruncation(),
              tokensIn,
              tokensOut,
              System.currentTimeMillis() - start,
              List.copyOf(degradations),
              diff.files().size(),
              CoverageReportBuilder.note(coverageLines));
      String summaryLine =
          ReviewRunFinisher.summaryLine(
              runId,
              status,
              depthPlan.effectiveDepth().name().toLowerCase(Locale.ROOT),
              stats,
              confirmedCount,
              rejectedCount,
              unverifiedCount);
      return ReviewRunFinisher.finish(
          trace,
          runId,
          request,
          status,
          stats,
          summaryLine,
          ReviewRunFinisher.suggestionsOf(toPublish));
    } catch (RuntimeException e) {
      // run 级不可恢复故障统一捕获：落盘 FAILED + trace，不阻塞 PR（02§4.2）
      return ReviewRunFinisher.finish(
          trace,
          runId,
          request,
          RunStatus.FAILED,
          ReviewRunFinisher.emptyStats(System.currentTimeMillis() - start),
          "run failed: " + e.getMessage());
    }
  }
}
