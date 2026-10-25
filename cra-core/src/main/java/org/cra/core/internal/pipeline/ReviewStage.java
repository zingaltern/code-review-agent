package org.cra.core.internal.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.ReviewAgentInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.ToolWhitelist;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;
import org.cra.common.context.PathGlobs;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.run.Depth;
import org.cra.context.api.ContextAssembler;
import org.cra.core.internal.AgentRunner;
import org.cra.core.internal.TraceWriter;

/**
 * M2 评审扇出编排（T2.2/T2.3，03§2.2/03§3/03§6）：视角路由 → 专角色触发判定 → 按 chunk 扇出（并发 8）→ 失败隔离汇聚 → 跨代理精确去重合并。
 *
 * <ul>
 *   <li>视角路由（02§4.3）：quick 1 视角 / standard 2 视角 / deep 全 4 视角；派发序即优先级序（dataflow → boundary →
 *       concurrency → resource——逻辑类与输入校验类覆盖最高频缺陷面，时序与资源类进 deep）；run 预算 ≥70% 档砍 1 视角（下限 1，02§4.4）；
 *   <li>专角色（03§6 v1.2）：Security 每 chunk 1 实例并行（强档，read_file+grep scoped，敏感路径/priority 注入 chunk
 *       内文件全文）；Perf Deep 档按 chunk；Test/Convention 全 PR 单实例（chunk 扇出后派发——预算阶梯下先保评审主力，70% 档砍 light 的
 *       Convention，02§4.4）；触发判定见各 trigger 方法；
 *   <li>上下文隔离（03§2.1）：每代理独立 brief（编排器组装、全文落盘），不共享中间推理与其他代理输出；
 *   <li>失败隔离（03§2.3/02§4.2）：单代理异常/超时/预算击穿不传染——收敛为该代理空产出 + 降级声明；全部代理 LLM 不可得才判 run FAILED（LLM
 *       全局故障），部分缺失则 DEGRADED 收尾；
 *   <li>汇聚（03§4.5）：候选并集 + 同 file/category/±3 行精确合并（{@link FindingMerger}，T2.8 精确去重的汇聚侧；语义去重留 M3）。
 * </ul>
 *
 * <p>共享可变状态仅限构造期创建的固定线程池（09§6，daemon 线程，宽度 = LLM 并发 8）。
 */
public final class ReviewStage {

  /** 扇出并发（02§4.5 LLM 并发信号量同宽；网关侧信号量仍为硬限）。 */
  static final int MAX_PARALLEL = 8;

  /** Security 敏感路径判定（注入 chunk 内文件全文，03§6 v1.2）。 */
  private static final Pattern SENSITIVE_PATH =
      Pattern.compile("(?i)(auth|crypto|secret|token|password|credential|jwt|security|keystore)");

  /** 依赖清单文件（依赖风险核对，03§3 Security 输入重点）。 */
  private static final Set<String> DEPENDENCY_MANIFESTS =
      Set.of("pom.xml", "build.gradle", "build.gradle.kts", "gradle.lockfile", "settings.gradle");

  /** Security 附加段字符上限（≈8k token，04§2.1 字符/3.5）。 */
  private static final int EXTRA_SECTION_MAX_CHARS = 28_000;

  private static final Set<String> EMPTY_PATHS = Set.of();

  private final ReviewCrew crew;
  private final AgentRunner agentRunner;
  private final ContextAssembler contextAssembler;
  private final ExecutorService pool;

  /**
   * @param crew 评审班组（专角色可为 null = 未装配）
   * @param agentRunner 隔离运行时
   * @param contextAssembler 上下文组装器
   */
  public ReviewStage(ReviewCrew crew, AgentRunner agentRunner, ContextAssembler contextAssembler) {
    this.crew = crew;
    this.agentRunner = agentRunner;
    this.contextAssembler = contextAssembler;
    this.pool =
        Executors.newFixedThreadPool(
            MAX_PARALLEL,
            runnable -> {
              Thread thread = new Thread(runnable, "cra-review");
              thread.setDaemon(true);
              return thread;
            });
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

  /** 单代理派发结果。 */
  private record DispatchOutcome(
      String agentId,
      String templateVersion,
      List<CandidateFinding> findings,
      long tokensIn,
      long tokensOut,
      boolean llmUnavailable,
      String llmError,
      List<String> schemaErrors,
      List<String> degradations,
      List<String> contextNotes,
      List<String> trimDecisions) {}

  /**
   * 视角路由（02§4.3 档位表：quick 1 / standard 2 / deep 3–4；返回序 = 派发优先级序）。
   *
   * @param depth 生效档位
   * @return 该档位的视角列表
   */
  public static List<BugHunterDefinition.Perspective> routePerspectives(Depth depth) {
    return switch (depth) {
      case QUICK -> List.of(BugHunterDefinition.Perspective.DATAFLOW);
      case STANDARD ->
          List.of(
              BugHunterDefinition.Perspective.DATAFLOW, BugHunterDefinition.Perspective.BOUNDARY);
      case DEEP ->
          List.of(
              BugHunterDefinition.Perspective.DATAFLOW,
              BugHunterDefinition.Perspective.BOUNDARY,
              BugHunterDefinition.Perspective.CONCURRENCY,
              BugHunterDefinition.Perspective.RESOURCE);
    };
  }

  /** 70% 档砍 1 视角（下限 1，02§4.4）。 */
  public static List<BugHunterDefinition.Perspective> degradePerspectives(
      List<BugHunterDefinition.Perspective> base) {
    if (base.size() <= 1) {
      return List.copyOf(base);
    }
    return base.subList(0, base.size() - 1);
  }

  /**
   * TestReviewer 触发判定（03§3：逻辑文件变更且测试变更比例低）。 测试文件 = src/test/ 路径；比例阈值 0.5 为实现默认（文档未钉死，覆盖声明如实说明）。
   *
   * @param diff 变更 diff
   * @return true 触发
   */
  public static boolean testReviewerTriggered(DiffResult diff) {
    int logic = 0;
    int test = 0;
    for (DiffFile file : diff.files()) {
      if (isTestPath(file.path())) {
        test++;
      } else {
        logic++;
      }
    }
    return logic > 0 && (test == 0 || (double) test / (test + logic) < 0.5);
  }

  /**
   * ConventionReviewer 触发判定（03§3：REVIEW.md 定义约定时）。
   *
   * @param policy 评审策略
   * @return true 触发
   */
  public static boolean conventionTriggered(ReviewPolicy policy) {
    return !policy.conventions().isEmpty();
  }

  /**
   * PerfReviewer 触发判定（03§3：Deep 档；perf 路径判定待 REVIEW.md 扩展，M2 以 Deep 档触发并在覆盖声明说明）。
   *
   * @param depthPlan 深度计划
   * @return true 触发
   */
  public static boolean perfTriggered(DepthPlan depthPlan) {
    return depthPlan.effectiveDepth() == Depth.DEEP;
  }

  /**
   * Security 附加段（03§6 v1.2）：chunk 内敏感路径/priority 命中文件的全文 + 依赖清单变更注记， 字符封顶（预算内注入）。
   *
   * @param chunk 评审 chunk
   * @param headFiles head 侧预取全文
   * @param policy 评审策略（priority_paths）
   * @return 附加段（无命中返回空串）
   */
  public static String securityExtraSection(
      ChunkPlanner.Chunk chunk, Map<String, FileContent> headFiles, ReviewPolicy policy) {
    StringBuilder out = new StringBuilder();
    for (DiffFile file : chunk.files()) {
      boolean manifest = DEPENDENCY_MANIFESTS.contains(file.path());
      boolean sensitive =
          SENSITIVE_PATH.matcher(file.path()).find()
              || PathGlobs.matchesAny(policy.priorityPaths(), file.path());
      if (manifest) {
        out.append("【依赖清单变更】").append(file.path()).append("（重点核对引入组件与版本放宽）\n");
      }
      if (sensitive) {
        FileContent content = headFiles.get(file.path());
        if (content != null && !content.binary() && content.text() != null) {
          out.append("### 敏感文件全文：")
              .append(file.path())
              .append("\n")
              .append(content.text())
              .append("\n");
        }
      }
      if (out.length() >= EXTRA_SECTION_MAX_CHARS) {
        out.append("（附加段已达预算上限，后续文件仅注入 diff）\n");
        break;
      }
    }
    if (out.length() > EXTRA_SECTION_MAX_CHARS) {
      out.setLength(EXTRA_SECTION_MAX_CHARS);
      out.append("\n（附加段截断至预算上限）\n");
    }
    return out.toString();
  }

  private static boolean isTestPath(String path) {
    return path.startsWith("src/test/") || path.contains("/src/test/");
  }

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
    List<BugHunterDefinition.Perspective> basePerspectives =
        routePerspectives(depthPlan.effectiveDepth());
    boolean securityEnabled = crew.securityReviewer() != null;
    boolean perfEnabled = perfTriggered(depthPlan) && crew.perfReviewer() != null;
    List<Future<DispatchOutcome>> futures = new ArrayList<>();
    List<DispatchOutcome> collected = new ArrayList<>();
    List<String> degradations = new ArrayList<>();
    List<String> contextNotes = new ArrayList<>();
    List<String> trimDecisions = new ArrayList<>();
    List<ChunkPlanner.Chunk> chunks = plan.chunks();
    int chunkIndex = 0;
    boolean stopped = false;
    boolean truncationReached = false;
    try {
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
                  ? degradePerspectives(basePerspectives)
                  : basePerspectives;
          int waveSize = effective.size() + (securityEnabled ? 1 : 0) + (perfEnabled ? 1 : 0);
          // chunk 原子派发：整个 chunk 的全部代理都能入队才取下一个 chunk——
          // 在飞派发严格 ≤ MAX_PARALLEL（网关侧信号量仍为硬限，02§4.5），降档相位对未开始的 chunk 语义确定
          while (chunkIndex < chunks.size() && futures.size() + waveSize <= MAX_PARALLEL) {
            ChunkPlanner.Chunk chunk = chunks.get(chunkIndex++);
            for (BugHunterDefinition.Perspective perspective : effective) {
              SubAgent<ReviewAgentInput> agent = agentOf(perspective);
              Map<String, FileContent> scoped = scopedFiles(chunk, headFiles);
              ContextInput contextInput =
                  buildContextInput(
                      chunk, headFiles, depthPlan, simplified, agent.budget().maxInputTokens());
              futures.add(
                  pool.submit(
                      () ->
                          dispatch(
                              agent,
                              new AgentScope(chunk.key(), chunk.files()),
                              scoped,
                              contextInput,
                              "",
                              prTitle,
                              commitMessages,
                              policySection,
                              runId,
                              trace)));
            }
            if (securityEnabled) {
              futures.add(
                  chunkDispatch(
                      crew.securityReviewer(),
                      chunk,
                      headFiles,
                      depthPlan,
                      simplified,
                      policy,
                      prTitle,
                      commitMessages,
                      policySection,
                      runId,
                      trace,
                      securityExtraSection(chunk, headFiles, policy)));
            }
            if (perfEnabled) {
              futures.add(
                  chunkDispatch(
                      crew.perfReviewer(),
                      chunk,
                      headFiles,
                      depthPlan,
                      simplified,
                      policy,
                      prTitle,
                      commitMessages,
                      policySection,
                      runId,
                      trace,
                      ""));
            }
          }
          if (futures.isEmpty()) {
            break;
          }
        }
        DispatchOutcome outcome = futures.remove(0).get();
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
        if (crew.testReviewer() != null && testReviewerTriggered(diff)) {
          futures.add(
              wholePrDispatch(
                  crew.testReviewer(),
                  plan,
                  headFiles,
                  testExtraSection(plan),
                  prTitle,
                  commitMessages,
                  policySection,
                  runId,
                  trace));
        }
        if (crew.conventionReviewer() != null
            && conventionTriggered(policy)
            && phase != RunBudgetTracker.Phase.DEGRADE) {
          futures.add(
              wholePrDispatch(
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
        while (!futures.isEmpty()) {
          DispatchOutcome outcome = futures.remove(0).get();
          budget.record(outcome.tokensIn(), outcome.tokensOut());
          collected.add(outcome);
          degradations.addAll(outcome.degradations());
          contextNotes.addAll(outcome.contextNotes());
          trimDecisions.addAll(outcome.trimDecisions());
        }
      } else {
        contextNotes.add("run 预算耗尽：Test/Convention 全 PR 级专角色未派发（02§4.4）");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("评审扇出被中断", e);
    } catch (ExecutionException e) {
      // dispatch 内已失败隔离；此处属防御性兜底（09§4：不吞异常事实）
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(
          "评审代理派发失败: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
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
    Map<String, String> templateVersions = new java.util.LinkedHashMap<>();
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
    if (unavailableCount > 0 && !llmUnavailable) {
      degradations.add("llm-unavailable-agents:" + unavailableCount);
      contextNotes.add(unavailableCount + " 个评审代理因 LLM 不可得缺失（失败隔离，02§4.2），其余代理产出照常汇聚");
    }
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

  /** 按 chunk 派发专角色（Security/Perf；上下文预算 = 该角色输入预算）。 */
  private Future<DispatchOutcome> chunkDispatch(
      SubAgent<ReviewAgentInput> agent,
      ChunkPlanner.Chunk chunk,
      Map<String, FileContent> headFiles,
      DepthPlan depthPlan,
      boolean simplified,
      ReviewPolicy policy,
      String prTitle,
      String commitMessages,
      String policySection,
      String runId,
      TraceWriter trace,
      String extraSection) {
    Map<String, FileContent> scoped = scopedFiles(chunk, headFiles);
    ContextInput contextInput =
        buildContextInput(chunk, headFiles, depthPlan, simplified, agent.budget().maxInputTokens());
    return pool.submit(
        () ->
            dispatch(
                agent,
                new AgentScope(chunk.key(), chunk.files()),
                scoped,
                contextInput,
                extraSection,
                prTitle,
                commitMessages,
                policySection,
                runId,
                trace));
  }

  /** 全 PR 级专角色派发（Test/Convention：仅 L0 diff，无窗口/全文；scope = 全部 chunk 文件）。 */
  private Future<DispatchOutcome> wholePrDispatch(
      SubAgent<ReviewAgentInput> agent,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      String extraSection,
      String prTitle,
      String commitMessages,
      String policySection,
      String runId,
      TraceWriter trace) {
    AgentScope scope = wholePrScope(plan);
    Map<String, FileContent> scoped =
        scopedFiles(new ChunkPlanner.Chunk(scope.key(), scope.files()), headFiles);
    return pool.submit(
        () ->
            dispatch(
                agent,
                scope,
                scoped,
                wholePrContextInput(scope, agent),
                extraSection,
                prTitle,
                commitMessages,
                policySection,
                runId,
                trace));
  }

  private SubAgent<ReviewAgentInput> agentOf(BugHunterDefinition.Perspective perspective) {
    // 按角色 id 匹配（03§2.2：视角 = 角色 id 后缀；不绑定定义类型，班组可注入任意 SubAgent 实现）
    String expectedId = "bug-hunter/" + perspective.id;
    return crew.bugHunters().stream()
        .filter(agent -> agent.role().id().equals(expectedId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("班组缺少视角: " + expectedId));
  }

  /**
   * 单代理派发（并行任务；失败隔离边界——任何异常收敛为空产出 + agent_output/failed 事件）。
   *
   * @param scope 代理范围（chunk 或全 PR；key 进 trace，files 为该代理的文件全集）
   */
  private DispatchOutcome dispatch(
      SubAgent<ReviewAgentInput> agent,
      AgentScope scope,
      Map<String, FileContent> scoped,
      ContextInput contextInput,
      String extraSection,
      String prTitle,
      String commitMessages,
      String policySection,
      String runId,
      TraceWriter trace) {
    try {
      AssembledContext context = contextAssembler.assemble(contextInput);
      boolean hasTools = agent.tools() != ToolWhitelist.NONE;
      ReviewAgentInput input =
          new ReviewAgentInput(
              prTitle,
              commitMessages,
              context.diffText(),
              context.windowText(),
              "", // L2 切片（04§1）：M2 未实现切片生成，dataflow/security 模板如实渲染占位说明
              extraSection,
              policySection,
              hasTools ? scoped.keySet() : EMPTY_PATHS);
      AgentBrief brief = agent.buildBrief(input);
      Path briefPath = trace.persistBrief(brief, scope.key());
      trace.emit(
          "agent_start",
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "brief_path", briefPath.toString(),
              "est_tokens", context.estimatedTokens(),
              "trim_decisions", context.trimDecisions()));
      AgentRunner.AgentRun<List<CandidateFinding>> run =
          agentRunner.execute(
              brief,
              new AgentRunner.ToolContext(brief.tools(), hasTools ? scoped : Map.of()),
              AgentRunner.findingsParser(runId, agent.role().id()),
              runId,
              trace);
      List<CandidateFinding> findings = run.output() == null ? List.of() : run.output();
      trace.emit(
          "agent_output",
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "findings", findings.size(),
              "termination", run.termination().name().toLowerCase(java.util.Locale.ROOT),
              "tokens", Map.of("in", run.tokensIn(), "out", run.tokensOut()),
              "latency_ms", run.latencyMs()));
      List<String> outcomeNotes = new ArrayList<>(context.coverageNotes());
      List<String> outcomeTrims = new ArrayList<>(context.trimDecisions());
      List<String> outcomeDegr = new ArrayList<>(run.degradations());
      if (run.termination() == AgentRunner.Termination.BUDGET_EXCEEDED) {
        // 03§1：预算硬限击穿的代理 = 该角色缺失，降级声明（不传染）
        outcomeDegr.add("agent-budget-exceeded:" + agent.role().id());
        outcomeNotes.add("代理 " + agent.role().id() + " 因预算硬限终止（" + run.errorMessage() + "）");
      }
      if (run.termination() == AgentRunner.Termination.TOOL_VIOLATION) {
        outcomeDegr.add("agent-tool-violation:" + agent.role().id());
        outcomeNotes.add("代理 " + agent.role().id() + " 触发工具隔离边界被终止（" + run.errorMessage() + "）");
      }
      return new DispatchOutcome(
          agent.role().id(),
          brief.templateVersion(),
          findings,
          run.tokensIn(),
          run.tokensOut(),
          run.llmUnavailable(),
          run.llmUnavailable() ? run.errorMessage() : null,
          run.termination() == AgentRunner.Termination.SCHEMA_VIOLATION
              ? run.schemaErrors()
              : List.of(),
          outcomeDegr,
          outcomeNotes,
          outcomeTrims);
    } catch (RuntimeException e) {
      // 失败隔离（03§2.3/09§4）：brief 组装/上下文等异常收敛为该代理空产出
      trace.emit(
          "agent_output",
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "findings", 0,
              "termination", "failed",
              "error", String.valueOf(e.getMessage())));
      List<String> outcomeDegr = new ArrayList<>();
      outcomeDegr.add("agent-failed:" + agent.role().id());
      return new DispatchOutcome(
          agent.role().id(),
          "",
          List.of(),
          0,
          0,
          false,
          null,
          List.of(),
          outcomeDegr,
          List.of("代理 " + agent.role().id() + " 执行失败（" + e.getMessage() + "），已隔离"),
          List.of());
    }
  }

  /** 代理范围（chunk 或全 PR）。 */
  private record AgentScope(String key, List<DiffFile> files) {

    AgentScope {
      files = List.copyOf(files == null ? List.of() : files);
    }
  }

  private static AgentScope wholePrScope(ChunkPlanner.Plan plan) {
    List<DiffFile> files = new ArrayList<>();
    plan.chunks().forEach(c -> files.addAll(c.files()));
    return new AgentScope("whole-pr", files);
  }

  private static Map<String, FileContent> scopedFiles(
      ChunkPlanner.Chunk chunk, Map<String, FileContent> headFiles) {
    Map<String, FileContent> scoped = new HashMap<>();
    for (DiffFile file : chunk.files()) {
      FileContent content = headFiles.get(file.path());
      if (content != null) {
        scoped.put(file.path(), content);
      }
    }
    return scoped;
  }

  private static String testExtraSection(ChunkPlanner.Plan plan) {
    StringBuilder sb = new StringBuilder();
    sb.append("### 本次变更的测试相关文件\n");
    boolean any = false;
    for (ChunkPlanner.Chunk chunk : plan.chunks()) {
      for (DiffFile file : chunk.files()) {
        if (isTestPath(file.path())) {
          any = true;
          sb.append("- ").append(file.path()).append("（").append(file.status()).append("）\n");
        }
      }
    }
    if (!any) {
      sb.append("-（本次变更未触及 src/test/ 路径）\n");
    }
    return sb.toString();
  }

  /** 全 PR 级代理的上下文：仅 L0 diff（无窗口/全文），L1 预算 = 该角色输入预算 − L0 估算 − 余量。 */
  private ContextInput wholePrContextInput(AgentScope scope, SubAgent<ReviewAgentInput> agent) {
    StringBuilder l0 = new StringBuilder();
    for (DiffFile file : scope.files()) {
      if (file.patch() != null) {
        l0.append(file.patch());
      }
    }
    long reserved = TokenMath.estimateTokens(l0.toString()) + 2_000;
    long l1Budget = Math.max(1_000, agent.budget().maxInputTokens() - reserved);
    return new ContextInput(scope.files(), Map.of(), 0, false, 1, l1Budget);
  }

  /** L1 预算 = 代理输入预算 − L0 估算 − 定界/策略占用余量（04§1 预算裁决）；DEGRADE 档收窄窗口并关全文（02§4.4）。 */
  private ContextInput buildContextInput(
      ChunkPlanner.Chunk chunk,
      Map<String, FileContent> headFiles,
      DepthPlan depthPlan,
      boolean simplified,
      long agentInputBudget) {
    boolean fullText = depthPlan.conditionalFullText() && !simplified;
    int window = simplified ? Math.min(depthPlan.windowLines(), 15) : depthPlan.windowLines();
    long reserved = TokenMath.estimateTokens(estimateChunkL0(chunk)) + 4_000;
    long l1Budget = Math.max(1_000, agentInputBudget - reserved);
    return new ContextInput(
        chunk.files(), headFiles, window, fullText, depthPlan.fullFileMaxLines(), l1Budget);
  }

  private static String estimateChunkL0(ChunkPlanner.Chunk chunk) {
    StringBuilder sb = new StringBuilder();
    for (DiffFile file : chunk.files()) {
      if (file.patch() != null) {
        sb.append(file.patch());
      }
    }
    return sb.toString();
  }
}
