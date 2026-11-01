package org.cra.core.internal.pipeline;

import static org.cra.core.internal.TraceEvents.AGENT_OUTPUT;
import static org.cra.core.internal.TraceEvents.AGENT_START;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.ReviewAgentInput;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.ToolWhitelist;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.DiffFile;
import org.cra.common.git.FileContent;
import org.cra.context.api.ContextAssembler;
import org.cra.core.internal.AgentRunner;
import org.cra.core.internal.CorePools;
import org.cra.core.internal.TaskHandle;
import org.cra.core.internal.TraceWriter;

/**
 * 评审代理派发执行器（03§2.1/03§2.3/09§6）：上下文输入组装 → brief 落盘 → 隔离运行时 → 失败隔离汇聚。
 *
 * <p>从 {@link ReviewStage} 拆出：调度策略留在 ReviewStage，单代理执行边界集中在此， 固定线程池随本类生命周期创建（宽度 = LLM 并发 8）。
 */
final class ReviewAgentDispatcher {

  /** 扇出并发（02§4.5 LLM 并发信号量同宽；网关侧信号量仍为硬限）。 */
  static final int MAX_PARALLEL = 8;

  private static final Set<String> EMPTY_PATHS = Set.of();

  private final AgentRunner agentRunner;
  private final ContextAssembler contextAssembler;
  private final ExecutorService pool;

  ReviewAgentDispatcher(AgentRunner agentRunner, ContextAssembler contextAssembler) {
    this.agentRunner = agentRunner;
    this.contextAssembler = contextAssembler;
    // 09§6：进程级共享池（常驻 server 按仓库缓存 pipeline，实例自建池会线性泄漏线程）
    this.pool = CorePools.reviewPool();
  }

  /** 单代理派发结果。 */
  record DispatchOutcome(
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
   * 按 chunk 派发（含 L2 跨文件切片；ADR-0014：仅 BH-dataflow / Security 注入，切片 token 从 L1 预算扣除）。
   *
   * @param slices 本 chunk 的 L2 切片（未启用/不可得为 {@link CrossFileSlices#empty()}）
   * @param layerContractSection 分层核对契约（仅索引可用时非空；模板未引用该段则无副作用）
   */
  TaskHandle<DispatchOutcome> submitChunk(
      SubAgent<ReviewAgentInput> agent,
      ChunkPlanner.Chunk chunk,
      Map<String, FileContent> headFiles,
      DepthPlan depthPlan,
      boolean simplified,
      String extraSection,
      String prTitle,
      String commitMessages,
      String policySection,
      String runId,
      TraceWriter trace,
      CrossFileSlices slices,
      String layerContractSection) {
    Map<String, FileContent> scoped = scopedFiles(chunk, headFiles);
    boolean sliceRole = CrossFileSliceAssembler.isSliceRole(agent.role().id());
    long sliceTokens = sliceRole ? slices.estimatedTokens() : 0L;
    ContextInput contextInput =
        buildContextInput(
            chunk,
            headFiles,
            depthPlan,
            simplified,
            agent.budget().maxInputTokens(),
            sliceTokens,
            TokenMath.estimateTokens(extraSection));
    return TaskHandle.submit(
        pool,
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
                trace,
                sliceRole ? slices : CrossFileSlices.empty(),
                sliceRole ? layerContractSection : ""));
  }

  /** 全 PR 级专角色派发（Test/Convention：仅 L0 diff，无窗口/全文；scope = 全部 chunk 文件）。 */
  TaskHandle<DispatchOutcome> submitWholePr(
      SubAgent<ReviewAgentInput> agent,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      String extraSection,
      String prTitle,
      String commitMessages,
      String policySection,
      String runId,
      TraceWriter trace) {
    WholePrScope whole = wholePr(plan, headFiles);
    return TaskHandle.submit(
        pool,
        () ->
            dispatch(
                agent,
                whole.scope(),
                whole.scoped(),
                wholePrContextInput(whole.scope(), agent),
                extraSection,
                prTitle,
                commitMessages,
                policySection,
                runId,
                trace,
                CrossFileSlices.empty(),
                ""));
  }

  /**
   * CrossChecker 派发（M4f-lite，03§3.1）：全 PR 单实例，输入 = diff 精简视图（L0 槽）+ 变更史（extraSection 槽，不可信）+
   * 产品段（reviewPolicySection 槽，可信）。材料在 {@link CrossCheckMaterials} 按 03§6 预算裁剪。
   */
  TaskHandle<DispatchOutcome> submitCrossCheck(
      SubAgent<ReviewAgentInput> agent,
      ChunkPlanner.Plan plan,
      Map<String, FileContent> headFiles,
      CrossCheckMaterials.Materials materials,
      String prTitle,
      String commitMessages,
      String runId,
      TraceWriter trace) {
    WholePrScope whole = wholePr(plan, headFiles);
    return TaskHandle.submit(
        pool,
        () ->
            executeGuarded(
                agent,
                whole.scope(),
                () -> {
                  boolean hasTools = agent.tools() != ToolWhitelist.NONE;
                  ReviewAgentInput input =
                      new ReviewAgentInput(
                          prTitle,
                          commitMessages,
                          materials.diffDigest(),
                          "",
                          "",
                          materials.historyText(),
                          materials.trustedSections(),
                          hasTools ? whole.scoped().keySet() : EMPTY_PATHS,
                          "");
                  long estimated =
                      TokenMath.estimateTokens(
                          materials.diffDigest()
                              + materials.historyText()
                              + materials.trustedSections());
                  return new Prepared(input, hasTools, estimated, 0L, List.of(), List.of());
                },
                whole.scoped(),
                runId,
                trace));
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
      TraceWriter trace,
      CrossFileSlices slices,
      String layerContractSection) {
    return executeGuarded(
        agent,
        scope,
        () -> {
          AssembledContext context = contextAssembler.assemble(contextInput);
          boolean hasTools = agent.tools() != ToolWhitelist.NONE;
          ReviewAgentInput input =
              new ReviewAgentInput(
                  prTitle,
                  commitMessages,
                  context.diffText(),
                  context.windowText(),
                  slices.text(), // L2 跨文件切片（04§1；ADR-0014，仅 dataflow/security 注入）
                  extraSection,
                  policySection,
                  hasTools ? scoped.keySet() : EMPTY_PATHS,
                  layerContractSection);
          return new Prepared(
              input,
              hasTools,
              context.estimatedTokens() + slices.estimatedTokens(),
              slices.estimatedTokens(),
              context.coverageNotes(),
              context.trimDecisions());
        },
        scoped,
        runId,
        trace);
  }

  /** 派发准备态（brief 组装输入 + 上下文统计）。 */
  private record Prepared(
      ReviewAgentInput input,
      boolean hasTools,
      long estimatedTokens,
      long slicesTokens,
      List<String> coverageNotes,
      List<String> trimDecisions) {}

  /** 执行边界：brief 组装 + 隔离运行时 + 事件 + 失败隔离（任何异常收敛为空产出，03§2.3/09§4）。 */
  private DispatchOutcome executeGuarded(
      SubAgent<ReviewAgentInput> agent,
      AgentScope scope,
      Supplier<Prepared> prepare,
      Map<String, FileContent> scoped,
      String runId,
      TraceWriter trace) {
    try {
      Prepared prepared = prepare.get();
      AgentBrief brief = agent.buildBrief(prepared.input());
      // 同一模块超过 3 文件会拆成多个 chunk，key 相同——后缀加文件集短哈希，避免并行代理写同一 brief 文件
      Path briefPath =
          trace.persistBrief(
              brief,
              scope.key()
                  + "-"
                  + Integer.toHexString(
                      scope.files().stream().map(DiffFile::path).sorted().toList().hashCode()));
      trace.emit(
          AGENT_START,
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "brief_path", briefPath.toString(),
              "est_tokens", prepared.estimatedTokens(),
              "slices_tokens", prepared.slicesTokens(),
              "trim_decisions", prepared.trimDecisions()));
      AgentRunner.AgentRun<List<CandidateFinding>> run =
          agentRunner.execute(
              brief,
              new AgentRunner.ToolContext(brief.tools(), prepared.hasTools() ? scoped : Map.of()),
              AgentRunner.findingsParser(runId, agent.role().id()),
              runId,
              trace);
      List<CandidateFinding> findings = run.output() == null ? List.of() : run.output();
      // findings_brief：逐条候选摘要（category file:L行 title）——未发布候选的可观测性
      // （M3 零发布归因曾因只看"findings 计数"而无法判断候选内容与拒因）
      List<String> findingsBrief =
          findings.stream()
              .map(
                  f ->
                      f.category()
                          + " "
                          + f.file()
                          + ":L"
                          + f.line()
                          + " "
                          + (f.title().length() > 60 ? f.title().substring(0, 60) : f.title()))
              .toList();
      trace.emit(
          AGENT_OUTPUT,
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "findings", findings.size(),
              "findings_brief", findingsBrief,
              "termination", run.termination().name().toLowerCase(Locale.ROOT),
              "tokens", Map.of("in", run.tokensIn(), "out", run.tokensOut()),
              "latency_ms", run.latencyMs()));
      List<String> outcomeNotes = new ArrayList<>(prepared.coverageNotes());
      List<String> outcomeTrims = new ArrayList<>(prepared.trimDecisions());
      List<String> outcomeDegr = new ArrayList<>(run.degradations());
      if (run.termination() == AgentRunner.Termination.BUDGET_EXCEEDED) {
        // 03§1：预算硬限击穿的代理 = 该角色缺失，降级声明（不传染）
        outcomeDegr.add(Degradations.agentBudgetExceeded(agent.role().id()));
        outcomeNotes.add("代理 " + agent.role().id() + " 因预算硬限终止（" + run.errorMessage() + "）");
      }
      if (run.termination() == AgentRunner.Termination.TOOL_VIOLATION) {
        outcomeDegr.add(Degradations.agentToolViolation(agent.role().id()));
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
          AGENT_OUTPUT,
          Map.of(
              "agent", agent.role().id(),
              "chunk", scope.key(),
              "findings", 0,
              "termination", "failed",
              "error", String.valueOf(e.getMessage())));
      List<String> outcomeDegr = new ArrayList<>();
      outcomeDegr.add(Degradations.agentFailed(agent.role().id()));
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

  private record WholePrScope(AgentScope scope, Map<String, FileContent> scoped) {}

  private static WholePrScope wholePr(ChunkPlanner.Plan plan, Map<String, FileContent> headFiles) {
    AgentScope scope = wholePrScope(plan);
    return new WholePrScope(
        scope, scopedFiles(new ChunkPlanner.Chunk(scope.key(), scope.files()), headFiles));
  }

  private static AgentScope wholePrScope(ChunkPlanner.Plan plan) {
    return new AgentScope("whole-pr", wholePrFiles(plan));
  }

  /** 全 PR 文件展平（派发与 CrossChecker 材料组装共用，避免两处各写一遍）。 */
  static List<DiffFile> wholePrFiles(ChunkPlanner.Plan plan) {
    List<DiffFile> files = new ArrayList<>();
    plan.chunks().forEach(c -> files.addAll(c.files()));
    return files;
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
      long agentInputBudget,
      long sliceTokens,
      long extraSectionTokens) {
    boolean fullText = depthPlan.conditionalFullText() && !simplified;
    int window = simplified ? Math.min(depthPlan.windowLines(), 15) : depthPlan.windowLines();
    // L2 切片与 Security 全文附加段都占用该代理的输入预算：从 L1 预算扣除，保证 brief 不越单代理硬限
    long reserved =
        TokenMath.estimateTokens(estimateChunkL0(chunk))
            + 4_000
            + Math.max(0, sliceTokens)
            + Math.max(0, extraSectionTokens);
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
