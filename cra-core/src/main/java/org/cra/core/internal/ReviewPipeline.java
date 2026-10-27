package org.cra.core.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.context.api.ContextAssembly;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.api.SemanticContextOptions;
import org.cra.core.api.SignoffOptions;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.ConvergenceGuard;
import org.cra.core.internal.pipeline.CrossCheckEvents;
import org.cra.core.internal.pipeline.CrossCheckHistory;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.DepthPolicy;
import org.cra.core.internal.pipeline.ExactDedupMerger;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PolicySections;
import org.cra.core.internal.pipeline.ReviewStage;
import org.cra.core.internal.pipeline.RunBudgetTracker;
import org.cra.core.internal.pipeline.SemanticContext;
import org.cra.core.internal.pipeline.SignoffAdvisor;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.api.SymbolIndex;
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
 * CoverageReportBuilder}，收尾见 {@link ReviewRunFinisher}，轮次超限收尾见 {@link
 * ConvergenceBreachHandler}。本类只保留阶段编排。
 *
 * <p>共享可变状态仅限 run 级预算账目（RunBudgetTracker，run 内单实例）；扇出线程池由 ReviewAgentDispatcher 持有（09§6）。
 */
public final class ReviewPipeline implements Orchestrator {

  private static final int MAX_PARALLEL_BATCHES = 4;

  /** 可疑账本标记的 Summary 告警阈值（05§1.3 规则 2：同一 PR ≥3 次疑似注入尝试）。 */
  private static final int LEDGER_SUSPECT_THRESHOLD = 3;

  /** CrossChecker 变更史路径集上限（语义索引增强；jgit PathFilterGroup 规模上限，ADR-0014）。 */
  private static final int CROSS_CHECK_PATH_CAP = 60;

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
  private final SignoffAdvisor signoffAdvisor;
  private final ExecutorService verifyPool;
  private final EventHistoryQuery eventHistoryQuery;

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
   * M2 形态兼容构造（CrossChecker 关闭、签字建议关闭——不改变既有 Summary 行为）。
   *
   * @param crossCheckerOptions CrossChecker 触发配置（null = 关闭）
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
        crossCheckerOptions,
        SignoffOptions.disabled());
  }

  /**
   * @param crossCheckerOptions CrossChecker 触发配置（05§5 agents.crosschecker；默认关闭不改变基线算术，04§2.2）
   * @param signoffOptions 签字建议配置（05§5 agents.signoff；默认关闭 = 既有行为不变）
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
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions) {
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
        crossCheckerOptions,
        signoffOptions,
        null,
        SemanticContextOptions.disabled(),
        null);
  }

  /**
   * M4f-full 形态构造（追加事件史查询端口；null = 未装配，CrossChecker 声明事件史不可得）。
   *
   * @param eventHistoryQuery 事件史检索端口（cross-checker 的 context 类外部证据面，ADR-0012）
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
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions,
      EventHistoryQuery eventHistoryQuery) {
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
        crossCheckerOptions,
        signoffOptions,
        eventHistoryQuery,
        SemanticContextOptions.disabled(),
        null);
  }

  /**
   * M4e 形态构造（追加语义上下文：L2 跨文件切片；ADR-0014）。
   *
   * @param semanticContextOptions 语义上下文配置（null = 关闭，行为与开关引入前一致）
   * @param symbolIndex 语义索引端口（null = 未装配；开启时按不可得声明）
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
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions,
      EventHistoryQuery eventHistoryQuery,
      SemanticContextOptions semanticContextOptions,
      SymbolIndex symbolIndex) {
    this.ledger = ledger;
    this.runRoot = runRoot;
    this.options = options == null ? PipelineOptions.defaults() : options;
    this.verifier = verifier;
    this.eventHistoryQuery = eventHistoryQuery;
    this.agentRunner = new AgentRunner(gateway);
    CrossCheckerOptions effectiveCrossChecker =
        crossCheckerOptions == null ? CrossCheckerOptions.disabled() : crossCheckerOptions;
    this.signoffAdvisor = new SignoffAdvisor(signoffOptions);
    this.reviewStage =
        new ReviewStage(
            crew, agentRunner, ContextAssembly.createContextAssembler(), effectiveCrossChecker);
    this.dataLoader =
        new PipelineDataLoader(
            adapter,
            ContextAssembly.createReviewPolicyParser(),
            localRepoContext,
            effectiveCrossChecker,
            eventHistoryQuery,
            semanticContextOptions,
            symbolIndex,
            Clock.systemUTC());
    this.gatekeeper = new FindingGatekeeper(dataLoader, localRepoContext);
    this.citedResolver = new CitedEvidenceResolver(localRepoContext, eventHistoryQuery);
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
              "mode", request.mode().name().toLowerCase(Locale.ROOT),
              "pr", request.prNumber(),
              "base", request.baseSha(),
              "head", request.headSha(),
              "depth", request.depth().name().toLowerCase(Locale.ROOT),
              "dry_run", request.dryRun()));

      // 第一道闸：commit 级幂等（02§4.1，只认完整发布 DONE|DEGRADED；命中即全量跳过——Summary
      // 保持历史原样，避免 CI 重跑反复 PATCH 旧评论；更新语义如有需要随流水线线统一加）
      Optional<LedgerRunMarker> completion = ledger.findCompletion(request.key());
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
      List<LedgerFindingMarker> publishedMarkers = ledger.publishedFindings(request.key());
      List<LedgerRunMarker> completedRuns = ledger.completedRuns(request.key());
      int suspectCount = ledger.suspectMarkerCount(request.key());
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
      // 07§5 M4a：风险分级与签字建议（确定性规则，不依赖 LLM；绑定本次 head commit）
      SignoffAdvice signoff = signoffAdvisor.assess(request, diff);
      trace.emit("signoff_advice", signoffEvent(signoff, signoffAdvisor.ruleVersion()));
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
        return ConvergenceBreachHandler.handle(
            request, runId, trace, publisher, diff, suspectWarning, roundPlan, start, signoff);
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
      String prTitle =
          switch (request.mode()) {
            case PULL_REQUEST -> "PR #" + request.prNumber();
            case COMMIT -> "push 直推 commit " + ReviewBodyRenderer.shortSha(request.headSha());
            case ISSUE -> "PR #" + request.prNumber() + "（夜间存量评审）";
          };
      String policySection =
          PolicySections.render(policy, PipelineDataLoader.hasReviewMdChange(diff));
      // M4e（ADR-0014）：语义上下文（默认关闭；关闭时零副作用，brief 与开关引入前一致）
      SemanticContext semanticContext = dataLoader.buildSemanticContext(request, depthPlan, trace);
      // M4f-lite/full：CrossChecker 变更史 + 产品事件史（03§3.1 输入 b）——仅在配置开启且非 quick 档时查询；
      // 语义索引开启时路径集增强为"变更文件 + 定义/引用/import 关联文件"（ADR-0014 决策 8）
      CrossCheckHistory crossCheckHistory =
          dataLoader.resolveCrossCheckHistory(
              request,
              diff,
              plan,
              depthPlan,
              semanticContext.enabled()
                  ? semanticContext.relatedPaths(allPlanFiles(plan), CROSS_CHECK_PATH_CAP)
                  : java.util.Set.of());
      CrossCheckEvents crossCheckEvents =
          dataLoader.resolveCrossCheckEvents(request, diff, plan, depthPlan, crossCheckHistory);
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
              crossCheckHistory,
              crossCheckEvents,
              semanticContext);
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
            "LLM 不可得: " + outcome.llmError(),
            List.of(),
            signoff);
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
              citedResolver,
              semanticContext);
      // v1.3.2 test 类分型：仅当候选含 test 类时采集测试文件清单（一次 listFiles，避免无谓 API 调用）
      List<String> testFileIndex =
          candidates.stream()
                  .anyMatch(
                      s -> s.finding().category() == org.cra.common.finding.FindingCategory.TEST)
              ? dataLoader.testFileIndex(request, trace)
              : List.of();
      String repoKey = request.repoOwner() + "/" + request.repoName();
      VerificationWave.WaveResult waveResult =
          wave.verify(candidates, policy, headFiles, reviewDiff, runId, testFileIndex, repoKey);
      degradations.addAll(waveResult.degradations());
      budget.record(waveResult.tokensIn(), waveResult.tokensOut());
      long tokensIn = outcome.tokensIn() + waveResult.tokensIn();
      long tokensOut = outcome.tokensOut() + waveResult.tokensOut();

      // T2.5/T2.6：晋升规则（M2 形态）+ Judge 仲裁与抽查复核（03§4.3/03§4.4）
      FindingPromoter.Outcome promotion =
          promoter.promote(
              runId, candidates, waveResult, headFiles, policy, budget, trace, repoKey);
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
              outcome.templateVersions(),
              signoff);
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
          ReviewRunFinisher.suggestionsOf(toPublish),
          signoff);
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

  /** 签字建议 trace 事件（05§1.4 signoff_advice；agent_approver 恒 false——报告字段显式声明）。 */
  private static Map<String, Object> signoffEvent(SignoffAdvice signoff, int rulesVersion) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("decision", signoff.decision());
    event.put("applicable", signoff.applicable());
    event.put(
        "tier", signoff.tier() == null ? "none" : signoff.tier().name().toLowerCase(Locale.ROOT));
    event.put("recommended_signers", signoff.recommendedSigners());
    event.put("commit", signoff.commitSha());
    event.put("stale", signoff.stale());
    event.put("agent_approver", signoff.agentApprover());
    event.put("rules", signoff.rules().stream().map(hit -> hit.id()).toList());
    event.put("rules_version", rulesVersion);
    return event;
  }

  /** 全 PR 文件展平（语义上下文路径集与切片口径一致：plan 内全部变更文件）。 */
  private static List<DiffFile> allPlanFiles(ChunkPlanner.Plan plan) {
    List<DiffFile> files = new ArrayList<>();
    plan.chunks().forEach(chunk -> files.addAll(chunk.files()));
    return List.copyOf(files);
  }
}
