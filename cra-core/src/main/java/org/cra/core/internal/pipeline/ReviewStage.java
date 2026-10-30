package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.ReviewAgentInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.context.api.ContextAssembler;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.internal.AgentRunner;
import org.cra.core.internal.TaskHandle;
import org.cra.core.internal.TraceWriter;
import org.cra.core.internal.pipeline.ReviewAgentDispatcher.DispatchOutcome;

/**
 * M2 评审扇出编排（T2.2/T2.3，03§2.2/03§3/03§6）：按 chunk 扇出（并发 8）→ 预算阶梯调度 → 失败隔离汇聚 → 跨代理精确去重合并。
 *
 * <ul>
 *   <li>视角与专角色触发规则见 {@link ReviewRoutingPolicy}（02§4.3/02§4.4/03§3）；
 *   <li>Prompt 附加段见 {@link ReviewPromptSections}（03§6 v1.2）；
 *   <li>单代理执行与上下文预算见 {@link ReviewAgentDispatcher}（03§2.1/03§2.3）；
 *   <li>上下文隔离（03§2.1）：每代理独立 brief（编排器组装、全文落盘），不共享中间推理与其他代理输出；
 *   <li>失败隔离（03§2.3/02§4.2）：单代理异常/超时/预算击穿不传染——收敛为该代理空产出 + 降级声明；全部代理 LLM 不可得才判 run FAILED（LLM
 *       全局故障），部分缺失则 DEGRADED 收尾；
 *   <li>汇聚（03§4.5）：候选并集 + 同 file/category/±3 行精确合并（{@link FindingMerger}，T2.8 精确去重的汇聚侧；语义去重留 M3）。
 * </ul>
 *
 * <p>本类只保留调度编排与结果汇聚；线程池由 {@link ReviewAgentDispatcher} 持有（09§6，daemon 线程，宽度 = LLM 并发 8）。
 */
public final class ReviewStage {

  /** 代理等待硬限（历史下限；实际值由 LlmGatewayConfig 推导，见 ReviewPipeline/TimeoutBudgets）。 */
  private static final long DEFAULT_AGENT_WAIT_TIMEOUT_MS = 360_000L;

  /** 扇出并发（02§4.5 LLM 并发信号量同宽；网关侧信号量仍为硬限）。 */
  static final int MAX_PARALLEL = ReviewAgentDispatcher.MAX_PARALLEL;

  private final ReviewCrew crew;
  private final ReviewAgentDispatcher dispatcher;
  private final CrossCheckerOptions crossCheckerOptions;
  private final long waitTimeoutMs;

  /**
   * M2 形态兼容构造（CrossChecker 关闭）。
   *
   * @param crew 评审班组（专角色可为 null = 未装配）
   * @param agentRunner 隔离运行时
   * @param contextAssembler 上下文组装器
   */
  public ReviewStage(ReviewCrew crew, AgentRunner agentRunner, ContextAssembler contextAssembler) {
    this(crew, agentRunner, contextAssembler, CrossCheckerOptions.disabled());
  }

  /**
   * @param crew 评审班组（专角色可为 null = 未装配）
   * @param agentRunner 隔离运行时
   * @param contextAssembler 上下文组装器
   * @param crossCheckerOptions CrossChecker 触发配置（05§5 agents.crosschecker；默认关闭）
   */
  public ReviewStage(
      ReviewCrew crew,
      AgentRunner agentRunner,
      ContextAssembler contextAssembler,
      CrossCheckerOptions crossCheckerOptions) {
    this(crew, agentRunner, contextAssembler, crossCheckerOptions, DEFAULT_AGENT_WAIT_TIMEOUT_MS);
  }

  /**
   * 测试/组合根形态：自定义外层等待 backstop。
   *
   * @param waitTimeoutMs 单个代理任务等待毫秒（须为正）
   */
  public ReviewStage(
      ReviewCrew crew,
      AgentRunner agentRunner,
      ContextAssembler contextAssembler,
      long waitTimeoutMs) {
    this(crew, agentRunner, contextAssembler, CrossCheckerOptions.disabled(), waitTimeoutMs);
  }

  /**
   * @param crossCheckerOptions CrossChecker 触发配置（05§5 agents.crosschecker；默认关闭）
   * @param waitTimeoutMs 单个代理任务等待毫秒（应覆盖网关最坏重试链，见 TimeoutBudgets）
   */
  public ReviewStage(
      ReviewCrew crew,
      AgentRunner agentRunner,
      ContextAssembler contextAssembler,
      CrossCheckerOptions crossCheckerOptions,
      long waitTimeoutMs) {
    if (waitTimeoutMs <= 0) {
      throw new IllegalArgumentException("waitTimeoutMs must be positive");
    }
    this.crew = crew;
    this.dispatcher = new ReviewAgentDispatcher(agentRunner, contextAssembler);
    this.crossCheckerOptions =
        crossCheckerOptions == null ? CrossCheckerOptions.disabled() : crossCheckerOptions;
    this.waitTimeoutMs = waitTimeoutMs;
  }

  /** 全部代理的汇聚产出（对齐原 RunOutcome 形状，publish 段无感）。 */
  public record StageOutcome(
      List<CandidateFinding> findings,
      long tokensIn,
      long tokensOut,
      boolean llmUnavailable,
      String llmError,
      List<String> schemaErrors,
      List<String> degradations,
      List<String> contextNotes,
      List<String> trimDecisions,
      boolean stoppedByBudget,
      boolean truncationReached,
      Map<String, String> templateVersions) {}

  /**
   * 执行评审扇出。
   *
   * @param runId run 标识
   * @param trace trace 写入器
   * @param diff 变更 diff（专角色触发判定）
   * @param policy 评审策略（conventions/priority_paths）
   * @param depthPlan 深度计划
   * @param plan 分 chunk 规划
   * @param headFiles head 侧预取全文
   * @param budget run 级预算账目（调用方持有）
   * @param prTitle PR 标题
   * @param commitMessages commit message 拼接
   * @param policySection 策略渲染段
   * @return 汇聚产出
   */
  public StageOutcome execute(
      String runId,
      TraceWriter trace,
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      RunBudgetTracker budget,
      String prTitle,
      String commitMessages,
      String policySection) {
    return execute(
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
        CrossCheckHistory.none());
  }

  /**
   * 执行评审扇出（含 CrossChecker，M4f-lite）。
   *
   * @param runId run 标识
   * @param trace trace 写入器
   * @param diff 变更 diff（专角色触发判定）
   * @param policy 评审策略（conventions/priority_paths + CrossChecker 产品段）
   * @param depthPlan 深度计划
   * @param plan 分 chunk 规划
   * @param headFiles head 侧预取全文
   * @param budget run 级预算账目（调用方持有）
   * @param prTitle PR 标题
   * @param commitMessages commit message 拼接
   * @param policySection 策略渲染段
   * @param crossCheckHistory 上下游变更史（未启用时 {@link CrossCheckHistory#none()}）
   * @return 汇聚产出
   */
  public StageOutcome execute(
      String runId,
      TraceWriter trace,
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      RunBudgetTracker budget,
      String prTitle,
      String commitMessages,
      String policySection,
      CrossCheckHistory crossCheckHistory) {
    return execute(
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
        CrossCheckEvents.none());
  }

  /**
   * 执行评审扇出（含 CrossChecker + 产品事件史，M4f-full）。
   *
   * @param crossCheckEvents 与变更相关的产品事件史（未启用/未命中时 {@link CrossCheckEvents#none()}）
   * @return 汇聚产出
   */
  public StageOutcome execute(
      String runId,
      TraceWriter trace,
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      RunBudgetTracker budget,
      String prTitle,
      String commitMessages,
      String policySection,
      CrossCheckHistory crossCheckHistory,
      CrossCheckEvents crossCheckEvents) {
    return execute(
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
        SemanticContext.disabled());
  }

  /**
   * 执行评审扇出（含 M4e 语义上下文：L2 跨文件切片，ADR-0014）。
   *
   * @param semanticContext 语义上下文（关闭时切片为空且无声明，行为与开关引入前一致）
   * @return 汇聚产出
   */
  public StageOutcome execute(
      String runId,
      TraceWriter trace,
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      RunBudgetTracker budget,
      String prTitle,
      String commitMessages,
      String policySection,
      CrossCheckHistory crossCheckHistory,
      CrossCheckEvents crossCheckEvents,
      SemanticContext semanticContext) {
    CrossCheckHistory crossHistory =
        crossCheckHistory == null ? CrossCheckHistory.none() : crossCheckHistory;
    CrossCheckEvents crossEvents =
        crossCheckEvents == null ? CrossCheckEvents.none() : crossCheckEvents;
    List<BugHunterDefinition.Perspective> basePerspectives =
        ReviewRoutingPolicy.routePerspectives(depthPlan.effectiveDepth());
    for (int drop = 0; drop < depthPlan.perspectiveDrop(); drop++) {
      basePerspectives = ReviewRoutingPolicy.degradePerspectives(basePerspectives);
    }
    boolean securityEnabled = crew.securityReviewer() != null;
    boolean perfEnabled =
        ReviewRoutingPolicy.perfTriggered(depthPlan) && crew.perfReviewer() != null;
    List<TaskHandle<DispatchOutcome>> futures = new ArrayList<>();
    List<DispatchOutcome> collected = new ArrayList<>();
    List<String> degradations = new ArrayList<>();
    List<String> contextNotes = new ArrayList<>();
    List<String> trimDecisions = new ArrayList<>();
    // chunk 级切片声明去重（同一句在每 chunk 重复没有信息量）
    LinkedHashSet<String> sliceNotes = new LinkedHashSet<>();
    List<ChunkPlanner.Chunk> chunks = plan.chunks();
    if (chunks.isEmpty()) {
      // 跳过/裁剪后无可评审文件是合法结果（policy skip_paths / hard-cap），不是代理故障：
      // 不得走空 L0 派发 → 全部 agent-failed → DEGRADED 的误判路径
      trace.emit("plan_empty", Map.of("files_changed", diff.files().size()));
      return new StageOutcome(
          List.of(),
          0,
          0,
          false,
          null,
          List.of(),
          List.of(),
          List.of("无可评审文件：变更 " + diff.files().size() + " 个文件全部被 skip/hard-cap 裁剪，本次不派发评审代理"),
          List.of(),
          false,
          false,
          Map.of());
    }
    int chunkIndex = 0;
    boolean stopped = false;
    boolean truncationReached = false;
    while (true) {
      RunBudgetTracker.Phase phase = budget.phase();
      if (phase == RunBudgetTracker.Phase.STOP) {
        // 100%：终止剩余派发，已派发的照常收集（02§4.4 DEGRADED 收尾）
        if (chunkIndex < chunks.size() && !stopped) {
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
                  chunks.size() - chunkIndex));
        }
        if (futures.isEmpty()) {
          break;
        }
      } else {
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
        // 70% 档：剩余 chunk 砍 1 视角（下限 1）+ 简化上下文（02§4.4）
        List<BugHunterDefinition.Perspective> effective =
            phase == RunBudgetTracker.Phase.DEGRADE
                ? ReviewRoutingPolicy.degradePerspectives(basePerspectives)
                : basePerspectives;
        int waveSize = effective.size() + (securityEnabled ? 1 : 0) + (perfEnabled ? 1 : 0);
        // chunk 原子派发：整个 chunk 的全部代理都能入队才取下一个 chunk——
        // 在飞派发严格 ≤ MAX_PARALLEL（网关侧信号量仍为硬限，02§4.5），降档相位对未开始的 chunk 语义确定
        while (chunkIndex < chunks.size() && futures.size() + waveSize <= MAX_PARALLEL) {
          ChunkPlanner.Chunk chunk = chunks.get(chunkIndex++);
          // M4e（ADR-0014）：本 chunk 的 L2 跨文件切片（关闭/不可得时为空，不产生声明）
          CrossFileSlices slices = semanticContext.slicesFor(chunk.files());
          sliceNotes.addAll(slices.notes());
          for (BugHunterDefinition.Perspective perspective : effective) {
            SubAgent<ReviewAgentInput> agent = agentOf(perspective);
            futures.add(
                dispatcher.submitChunk(
                    agent,
                    chunk,
                    headFiles,
                    depthPlan,
                    simplified,
                    "",
                    prTitle,
                    commitMessages,
                    policySection,
                    runId,
                    trace,
                    slices));
          }
          if (securityEnabled) {
            futures.add(
                dispatcher.submitChunk(
                    crew.securityReviewer(),
                    chunk,
                    headFiles,
                    depthPlan,
                    simplified,
                    ReviewPromptSections.securityExtraSection(chunk, headFiles, policy),
                    prTitle,
                    commitMessages,
                    policySection,
                    runId,
                    trace,
                    slices));
          }
          if (perfEnabled) {
            futures.add(
                dispatcher.submitChunk(
                    crew.perfReviewer(),
                    chunk,
                    headFiles,
                    depthPlan,
                    simplified,
                    "",
                    prTitle,
                    commitMessages,
                    policySection,
                    runId,
                    trace,
                    slices));
          }
        }
        if (futures.isEmpty()) {
          break;
        }
      }
      DispatchOutcome outcome = await(futures.remove(0), degradations);
      budget.record(outcome.tokensIn(), outcome.tokensOut());
      collected.add(outcome);
      degradations.addAll(outcome.degradations());
      contextNotes.addAll(outcome.contextNotes());
      trimDecisions.addAll(outcome.trimDecisions());
    }
    // 全 PR 级专角色（Test/Convention）：chunk 扇出后派发——预算阶梯下先保评审主力；
    // 70% 档砍 light 的 Convention（02§4.4），100% 终止
    RunBudgetTracker.Phase phase = budget.phase();
    if (phase != RunBudgetTracker.Phase.STOP) {
      if (crew.testReviewer() != null && ReviewRoutingPolicy.testReviewerTriggered(diff)) {
        futures.add(
            dispatcher.submitWholePr(
                crew.testReviewer(),
                plan,
                headFiles,
                ReviewPromptSections.testExtraSection(plan),
                prTitle,
                commitMessages,
                policySection,
                runId,
                trace));
      }
      if (crew.conventionReviewer() != null
          && ReviewRoutingPolicy.conventionTriggered(policy)
          && phase != RunBudgetTracker.Phase.DEGRADE) {
        futures.add(
            dispatcher.submitWholePr(
                crew.conventionReviewer(),
                plan,
                headFiles,
                "",
                prTitle,
                commitMessages,
                policySection,
                runId,
                trace));
      }
      // M4f-lite/full CrossChecker（03§3.1）：全 PR 单实例；配置开启 + standard/deep 档 + 至少一个模式有数据
      boolean crossCheck =
          ReviewRoutingPolicy.crossCheckTriggered(
              depthPlan, policy, crew, crossCheckerOptions, crossHistory, crossEvents);
      if (crossCheck) {
        boolean assumptionMode =
            crossCheckerOptions.modeEnabled(CrossCheckerOptions.Mode.ASSUMPTION)
                && (!policy.productContext().isEmpty()
                    || !policy.knownIncidents().isEmpty()
                    || crossHistory.hasData()
                    || crossEvents.hasData());
        boolean sniffMode =
            crossCheckerOptions.modeEnabled(CrossCheckerOptions.Mode.SNIFF)
                && !policy.sniffTests().isEmpty();
        CrossCheckMaterials.Materials materials =
            CrossCheckMaterials.build(
                ReviewAgentDispatcher.wholePrFiles(plan),
                assumptionMode ? crossHistory.entries() : List.of(),
                crossCheckerOptions.historyWeeks(),
                policy,
                assumptionMode,
                sniffMode,
                assumptionMode ? crossEvents.entries() : List.of());
        contextNotes.addAll(crossHistory.notes());
        contextNotes.addAll(crossEvents.notes());
        contextNotes.addAll(materials.notes());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("triggered", true);
        event.put("modes", ReviewRoutingPolicy.activeModes(assumptionMode, sniffMode));
        event.put("history_available", crossHistory.available());
        event.put("history_commits", crossHistory.entries().size());
        event.put("events_available", crossEvents.available());
        event.put("events_count", crossEvents.entries().size());
        event.put("diff_tokens", TokenMath.estimateTokens(materials.diffDigest()));
        event.put("history_tokens", TokenMath.estimateTokens(materials.historyText()));
        event.put(
            "event_tokens",
            TokenMath.estimateTokens(
                CrossCheckMaterials.eventsText(
                    assumptionMode ? crossEvents.entries() : List.of(),
                    crossCheckerOptions.historyWeeks())));
        event.put("context_tokens", TokenMath.estimateTokens(materials.trustedSections()));
        trace.emit("crosscheck", event);
        futures.add(
            dispatcher.submitCrossCheck(
                crew.crossChecker(),
                plan,
                headFiles,
                materials,
                prTitle,
                commitMessages,
                runId,
                trace));
      } else if (crossCheckerOptions.enabled()) {
        // 覆盖声明（07 T1.4：段落缺失时相关检查优雅跳过——跳过原因必须可见，不静默）
        String reason =
            ReviewRoutingPolicy.crossCheckSkipReason(
                depthPlan, policy, crew, crossHistory, crossEvents);
        contextNotes.add("CrossChecker 未触发：" + reason);
        trace.emit("crosscheck", Map.of("triggered", false, "reason", reason));
      }
      while (!futures.isEmpty()) {
        DispatchOutcome outcome = await(futures.remove(0), degradations);
        budget.record(outcome.tokensIn(), outcome.tokensOut());
        collected.add(outcome);
        degradations.addAll(outcome.degradations());
        contextNotes.addAll(outcome.contextNotes());
        trimDecisions.addAll(outcome.trimDecisions());
      }
    } else {
      contextNotes.add("run 预算耗尽：Test/Convention 全 PR 级专角色未派发（02§4.4）");
      if (crossCheckerOptions.enabled() && crew.crossChecker() != null) {
        contextNotes.add("run 预算耗尽：CrossChecker 未派发（02§4.4）");
        trace.emit("crosscheck", Map.of("triggered", false, "reason", "budget-exhausted"));
      }
    }
    // 终态复查（02§4.4）：消耗只增不减，最后一批调用跨越 85%/100% 线时截断同样生效
    RunBudgetTracker.Phase finalPhase = budget.phase();
    if (finalPhase.ordinal() >= RunBudgetTracker.Phase.TRUNCATE.ordinal()) {
      truncationReached = true;
      if (!degradations.contains("budget-truncate-85")) {
        degradations.add("budget-truncate-85");
        trace.emit("budget_check", Map.of("phase", "truncate-final", "ratio", budget.ratio()));
      }
    }

    List<CandidateFinding> all = new ArrayList<>();
    long tokensIn = 0;
    long tokensOut = 0;
    long unavailableCount = 0;
    String llmError = null;
    List<String> schemaErrors = new ArrayList<>();
    Map<String, String> templateVersions = new LinkedHashMap<>();
    for (DispatchOutcome outcome : collected) {
      all.addAll(outcome.findings());
      if (outcome.templateVersion() != null && !outcome.templateVersion().isBlank()) {
        templateVersions.putIfAbsent(outcome.agentId(), outcome.templateVersion());
      }
      tokensIn += outcome.tokensIn();
      tokensOut += outcome.tokensOut();
      if (outcome.llmUnavailable()) {
        unavailableCount++;
        if (llmError == null) {
          llmError = outcome.llmError();
        }
      }
      schemaErrors.addAll(outcome.schemaErrors());
    }
    // 失败隔离语义（02§4.2）：全部代理 LLM 不可得 = 全局故障 → run FAILED；部分缺失 = DEGRADED 声明
    boolean llmUnavailable = !collected.isEmpty() && unavailableCount == collected.size();
    long failedCount =
        collected.stream()
            .filter(o -> o.degradations().stream().anyMatch(d -> d.startsWith("agent-failed:")))
            .count();
    if (!collected.isEmpty() && failedCount == collected.size() && !llmUnavailable) {
      // 全部代理执行失败（异常/brief 落盘等，非 LLM 不可得）= 无可信评审产出——不得静默 SUCCESS
      degradations.add("all-review-agents-failed");
      contextNotes.add("全部 " + collected.size() + " 个评审代理执行失败（失败隔离已吸收，02§4.2），本次无可信评审产出");
    }
    if (unavailableCount > 0 && !llmUnavailable) {
      degradations.add("llm-unavailable-agents:" + unavailableCount);
      contextNotes.add(unavailableCount + " 个评审代理因 LLM 不可得缺失（失败隔离，02§4.2），其余代理产出照常汇聚");
    }
    contextNotes.addAll(semanticContext.coverageNotes());
    contextNotes.addAll(sliceNotes);
    // 汇聚：跨代理精确去重合并（03§4.5；T2.8 汇聚侧）
    List<CandidateFinding> merged = FindingMerger.mergePrecise(all, trace);
    if (merged.size() < all.size()) {
      contextNotes.add(
          "跨代理精确去重合并 " + (all.size() - merged.size()) + " 条（同 file/category/±3 行，保留 severity 高者）");
    }
    return new StageOutcome(
        merged,
        tokensIn,
        tokensOut,
        llmUnavailable,
        llmError,
        schemaErrors,
        degradations,
        contextNotes,
        trimDecisions,
        stopped,
        truncationReached,
        templateVersions);
  }

  /**
   * 有界等待代理产出（09§6 强制：带超时 Future）。超时即取消任务并收敛为该代理空产出 + 降级声明， 不把整个 wave 卡死；网关/LLM
   * 的单次调用超时仍在其内部生效，这里是最后一道硬限。
   *
   * <p>共享池下 360s 含排队时间：按任务是否已开始区分 queue-timeout（池被其它 run 占满）与 execution-timeout（代理卡住），
   * 两者分别声明，避免把排队误报成执行超时。
   */
  private DispatchOutcome await(TaskHandle<DispatchOutcome> task, List<String> degradations) {
    try {
      return task.future().get(waitTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      boolean started = task.started();
      task.cancel();
      String degradation = started ? "agent-timeout" : "agent-queue-timeout";
      degradations.add(degradation);
      return new DispatchOutcome(
          "unknown",
          null,
          List.of(),
          0,
          0,
          false,
          null,
          List.of(),
          List.of(),
          List.of(started ? "代理执行超时（已开始）" : "代理排队超时（未开始执行）"),
          List.of());
    } catch (InterruptedException e) {
      task.cancel();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("评审扇出被中断", e);
    } catch (ExecutionException e) {
      task.cancel();
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(
          "评审代理派发失败: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
    }
  }

  private SubAgent<ReviewAgentInput> agentOf(BugHunterDefinition.Perspective perspective) {
    // 按角色 id 匹配（03§2.2：视角 = 角色 id 后缀；不绑定定义类型，班组可注入任意 SubAgent 实现）
    String expectedId = "bug-hunter/" + perspective.id;
    return crew.bugHunters().stream()
        .filter(agent -> agent.role().id().equals(expectedId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("班组缺少视角: " + expectedId));
  }
}
