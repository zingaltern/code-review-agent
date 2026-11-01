package org.cra.core.internal;

import static org.cra.core.internal.TraceEvents.AGENT_BUDGET_EXCEEDED;
import static org.cra.core.internal.TraceEvents.TOOL_CALL;
import static org.cra.core.internal.TraceEvents.TOOL_RESULT;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.TemplateSupport;
import org.cra.agents.api.ToolWhitelist;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.FileContent;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;
import org.cra.llm.api.ResponseMode;

/**
 * 子代理隔离运行时（T2.1，03§1/03§2）：LLM 循环 + 受限工具 + 三重预算硬限 + 失败隔离。
 *
 * <ul>
 *   <li>上下文隔离：一切输入来自 brief；工具结果以 UNTRUSTED 定界追加进对话（04§3.3）；
 *   <li>工具白名单：不在白名单或路径越界（不在 scoped 集）→ 立即终止记 TOOL_VIOLATION——隔离边界实现层硬编码，不靠 prompt（04§5 反模式 8）；
 *   <li>三重预算硬限：token（每轮输入估算 ≤ 输入预算、累计输出 ≤ 输出预算）、轮次（≤ maxTurns 次调用）、时间（≤ timeoutMs）——超限即杀记
 *       BUDGET_EXCEEDED（03§1）；
 *   <li>失败隔离：任何异常/不可得收敛为结果对象，绝不抛穿扇出层（02§4.2/09§4）——单代理失败不传染（03§2.3）；
 *   <li>结构化出口：最终输出交由角色解析器校验，失败携错误重试 ≤2 次后丢弃记 SCHEMA_VIOLATION（04§6；重试受轮次预算约束）。
 * </ul>
 *
 * <p>最终输出的 schema 解析由调用方注入（评审=findings、验证=verdicts——T2.4 复用本运行时）；工具协议见
 * prompts/shared/tool-protocol.md： 模型输出含 "tool" 字段的 JSON 对象即工具调用，否则视为最终结果。
 */
public final class AgentRunner {

  /** schema 重试上限（04§6：≤2 次）。 */
  static final int MAX_SCHEMA_RETRIES = 2;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LlmGateway gateway;

  public AgentRunner(LlmGateway gateway) {
    this.gateway = gateway;
  }

  /** 最终输出解析器（角色定制点）：解析失败携逐条错误（04§6 携错误重试的载体）。 */
  public interface FinalOutputParser<T> {

    /**
     * 解析最终输出。
     *
     * @param json 模型回复内容
     * @param model 实际响应模型（finding origin.model 用）
     * @return 解析结果（errors 非空 = 校验失败）
     */
    Parsed<T> parse(String json, String model);

    /** 解析结果（{@code errors} 非空 = 校验失败，与 {@link #parse} 契约一致）。 */
    record Parsed<T>(T value, List<String> errors) {

      public static <T> Parsed<T> of(T value) {
        return new Parsed<>(value, List.of());
      }

      public static <T> Parsed<T> invalid(List<String> errors) {
        return new Parsed<>(null, List.copyOf(errors));
      }
    }
  }

  /** 执行终态（03§1 预算硬限 + 02§4.2 降级语义；随 agent_output 事件入 trace）。 */
  public enum Termination {
    /** 正常完成 */
    COMPLETED,
    /** 三重预算超限即杀（03§1：token/轮次/时间） */
    BUDGET_EXCEEDED,
    /** LLM 不可得（02§4.2：重试耗尽/排队超时，网关降级结果） */
    LLM_UNAVAILABLE,
    /** schema 重试耗尽后丢弃（04§6） */
    SCHEMA_VIOLATION,
    /** 工具白名单/作用域违规——立即终止（03§2.3 硬边界） */
    TOOL_VIOLATION,
    /** 未预期异常（失败隔离收敛点，02§4.2） */
    FAILED
  }

  /**
   * 一次隔离执行的结果（失败隔离：任何终态都不抛异常）。
   *
   * @param output 解析后的最终输出（非 COMPLETED 为 null）
   * @param schemaErrors schema 校验错误（SCHEMA_VIOLATION 时非空）
   * @param tokensIn 累计输入 token（全部轮次）
   * @param tokensOut 累计输出 token
   * @param latencyMs 总耗时（含全部轮次）
   * @param turns LLM 调用轮数（≤ maxTurns）
   * @param termination 终态
   * @param errorMessage 失败原因（COMPLETED 为 null）
   * @param model 实际响应模型（任一轮成功响应即记录）
   * @param degradations 降级事件（schema 重试等）
   * @param <T> 最终输出类型
   */
  public record AgentRun<T>(
      T output,
      List<String> schemaErrors,
      long tokensIn,
      long tokensOut,
      long latencyMs,
      int turns,
      Termination termination,
      String errorMessage,
      String model,
      List<String> degradations) {

    /**
     * 兼容视图：LLM 不可得终态（02§4.2 run FAILED 判定用）。
     *
     * @return true 若 LLM 不可得
     */
    public boolean llmUnavailable() {
      return termination == Termination.LLM_UNAVAILABLE;
    }

    /**
     * 兼容视图：不可得原因（验证波/Judge 的错误注记用，02§4.2）。
     *
     * @return 错误消息（无则 null）
     */
    public String llmError() {
      return errorMessage;
    }
  }

  /** 工具上下文：白名单 + 预取并裁剪到作用域的文件集（scoped 即边界，03§2.1 白名单在 brief 生成时确定）。 */
  public record ToolContext(ToolWhitelist whitelist, Map<String, FileContent> scopedFiles) {

    /**
     * 无工具形态（M0：上下文全部内联）。
     *
     * @return 空白名单上下文
     */
    public static ToolContext none() {
      return new ToolContext(ToolWhitelist.NONE, Map.of());
    }
  }

  /**
   * 评审角色的 findings 解析器（FindingJsonCodec 契约；runId/role 由本方法绑定）。
   *
   * @param runId run 标识
   * @param agentRole 产出角色标识
   * @return findings 解析器
   */
  public static FinalOutputParser<List<CandidateFinding>> findingsParser(
      String runId, String agentRole) {
    return (json, model) -> {
      FindingJsonCodec.ParseResult result = FindingJsonCodec.parse(json, runId, agentRole, model);
      return result.errors().isEmpty()
          ? FinalOutputParser.Parsed.of(result.findings())
          : FinalOutputParser.Parsed.invalid(result.errors());
    };
  }

  /**
   * 判定/终裁便捷入口（T2.4/T2.6）：无工具上下文 + 角色解析器；trace 供运行时事件（07§6.5）。
   *
   * @param brief 自包含简报
   * @param runId run 标识
   * @param parser 最终输出解析器（调用方注入角色 schema 适配）
   * @param trace trace 写入器
   * @param <T> 最终输出类型
   * @return 执行结果（绝不抛异常）
   */
  public <T> AgentRun<T> executeParsed(
      AgentBrief brief, String runId, FinalOutputParser<T> parser, TraceWriter trace) {
    return execute(brief, ToolContext.none(), parser, runId, trace);
  }

  /**
   * 执行一次隔离代理调用（工具循环 + 预算硬限 + 失败隔离）。
   *
   * @param brief 自包含简报
   * @param tools 工具上下文（作用域文件集）
   * @param parser 最终输出解析器
   * @param runId run 标识
   * @param trace trace 写入器（tool_call/tool_result/agent_budget_exceeded 事件先行，07§6.5）
   * @param <T> 最终输出类型
   * @return 执行结果（绝不抛异常）
   */
  public <T> AgentRun<T> execute(
      AgentBrief brief,
      ToolContext tools,
      FinalOutputParser<T> parser,
      String runId,
      TraceWriter trace) {
    LoopState state = new LoopState(System.currentTimeMillis(), brief.userPrompt());
    List<String> degradations = state.degradations;
    ScopedToolSet toolSet = new ScopedToolSet(tools.scopedFiles());
    try {
      while (true) {
        Optional<AgentRun<T>> breach = preFlightBreach(brief, state, trace, degradations);
        if (breach.isPresent()) {
          return breach.get();
        }
        state.turns++;
        LlmResponse response =
            gateway.complete(
                new LlmRequest(
                    brief.systemPrompt(),
                    state.conversation.toString(),
                    ResponseMode.JSON,
                    (int) brief.budget().maxOutputTokens(),
                    brief.role().id(),
                    org.cra.llm.api.ModelTier.valueOf(brief.modelTier().name())));
        Optional<AgentRun<T>> absorbed =
            absorbResponse(brief, response, state, trace, degradations);
        if (absorbed.isPresent()) {
          return absorbed.get();
        }
        JsonNode root = readJsonOrNull(response.content());
        ToolTurn<T> toolTurn = handleToolTurn(root, brief, toolSet, state, trace, degradations);
        switch (toolTurn.kind()) {
          case VIOLATION -> {
            return toolTurn.terminal();
          }
          case CONTINUE -> {
            continue;
          }
          case NOT_A_TOOL_CALL -> {
            // 最终输出 → 角色解析器（04§6：失败携错误重试 ≤2，不放宽 schema）
          }
        }
        FinalStep<T> finalStep =
            finalizeOutput(parser.parse(response.content(), state.lastModel), degradations, state);
        switch (finalStep.kind()) {
          case COMPLETED, EXHAUSTED -> {
            return finalStep.terminal();
          }
          case RETRY -> {
            // 对话已追加系统重试提示，回到循环头（受轮次/输入/时间硬限约束）
          }
        }
      }
    } catch (RuntimeException e) {
      // 失败隔离（03§2.3/09§4）：单代理异常不传染，收敛为结果对象
      return terminal(
          state,
          Termination.FAILED,
          null,
          List.of(),
          e.getClass().getSimpleName() + ": " + e.getMessage(),
          state.lastModel,
          degradations);
    }
  }

  /**
   * 单次隔离执行的循环内可变状态（执行器私有；字段仅在本文件循环 helper 间传递，替代原 execute 的裸局部变量组）。
   *
   * <p>{@code start} 为执行起始毫秒（原方法局部变量上提）：所有终态的 latencyMs 由 {@link AgentRunner#terminal} 从它推导， 时间硬限
   * deadline = start + timeoutMs；其余字段语义与原局部变量逐一对应。
   */
  private static final class LoopState {

    /** 执行起始毫秒（latencyMs 与时间硬限的基准）。 */
    private final long start;

    /** 累计输入 token（全部轮次；UNAVAILABLE 轮的响应 token 也计入）。 */
    private long tokensIn;

    /** 累计输出 token（全部轮次）。 */
    private long tokensOut;

    /** LLM 调用轮数（≤ maxTurns；schema 重试同样消耗轮次）。 */
    private int turns;

    /** 已发生的 schema 重试次数（≤ {@link AgentRunner#MAX_SCHEMA_RETRIES}）。 */
    private int schemaRetries;

    /** 实际响应模型（任一轮成功响应即记录；UNAVAILABLE 轮不更新）。 */
    private String lastModel;

    /** 对话正文（userPrompt 起，工具结果/重试提示以定界追加）。 */
    private final StringBuilder conversation;

    /** 降级事件（schema 重试、模型降级链；随终态 AgentRun 原样透出）。 */
    private final List<String> degradations = new ArrayList<>();

    /**
     * @param start 执行起始毫秒
     * @param userPrompt 简报用户内容（对话初始正文）
     */
    private LoopState(long start, String userPrompt) {
      this.start = start;
      this.conversation = new StringBuilder(userPrompt);
    }
  }

  /**
   * 统一终态构建（原 execute 各出口的 new AgentRun&lt;&gt;(...) 收敛点）：tokens/turns/latency/degradations 取自
   * {@code state}， value/errors/termination/message/model 由调用点显式传入以保留各出口的字段差异（如 UNAVAILABLE 的
   * model=null、 SCHEMA_VIOLATION 的 errors=parsed.errors()）。
   *
   * @param state 循环状态（tokensIn/tokensOut/turns/latencyMs 基准与降级事件）
   * @param termination 终态
   * @param value 解析后的最终输出（非 COMPLETED 为 null）
   * @param errors schema 校验错误（SCHEMA_VIOLATION 时非空，其余为空列表）
   * @param message 失败原因（COMPLETED 为 null）
   * @param lastModel 实际响应模型（UNAVAILABLE 出口调用点显式传 null）
   * @param degradations 降级事件
   * @param <T> 最终输出类型
   * @return 终态 AgentRun（elapsed = 当前毫秒 - state.start）
   */
  private static <T> AgentRun<T> terminal(
      LoopState state,
      Termination termination,
      T value,
      List<String> errors,
      String message,
      String lastModel,
      List<String> degradations) {
    return new AgentRun<>(
        value,
        errors,
        state.tokensIn,
        state.tokensOut,
        System.currentTimeMillis() - state.start,
        state.turns,
        termination,
        message,
        lastModel,
        degradations);
  }

  /**
   * 轮次/输入/时间三个硬限的前置检查（原循环头部三个 if 块原样搬移，检查顺序与 emit/消息文案逐字不变）。
   *
   * @param brief 自包含简报（预算上限与角色 id 来源）
   * @param state 循环状态（轮数、对话、tokens、起始时间）
   * @param trace trace 写入器（超限先 emit {@code agent_budget_exceeded} 再收敛终态）
   * @param degradations 降级事件（透传给终态）
   * @param <T> 最终输出类型
   * @return 超限时的 BUDGET_EXCEEDED 终态；三项均未超限为 empty
   */
  private static <T> Optional<AgentRun<T>> preFlightBreach(
      AgentBrief brief, LoopState state, TraceWriter trace, List<String> degradations) {
    // 轮次硬限（03§6 轮次列 = LLM 调用次数上限；schema 重试同样消耗轮次）
    if (state.turns >= brief.budget().maxTurns()) {
      trace.emit(AGENT_BUDGET_EXCEEDED, Map.of("agent", brief.role().id(), "limit", "turns"));
      return Optional.of(
          terminal(
              state,
              Termination.BUDGET_EXCEEDED,
              null,
              List.of(),
              "轮次硬限（maxTurns=" + brief.budget().maxTurns() + "）",
              state.lastModel,
              degradations));
    }
    // 输入预算：每轮 prompt（system + 对话，含工具结果）估算超限即杀（03§6 输入预算/chunk）
    long estimatedInput = TokenMath.estimateTokens(brief.systemPrompt() + state.conversation);
    if (estimatedInput > brief.budget().maxInputTokens()) {
      trace.emit(
          AGENT_BUDGET_EXCEEDED, Map.of("agent", brief.role().id(), "limit", "input-tokens"));
      return Optional.of(
          terminal(
              state,
              Termination.BUDGET_EXCEEDED,
              null,
              List.of(),
              "输入超预算（估算 " + estimatedInput + " > " + brief.budget().maxInputTokens() + "）",
              state.lastModel,
              degradations));
    }
    // 时间硬限（03§1：超限即杀；deadline 恒为 start + timeoutMs，与原循环前置计算一致）
    if (System.currentTimeMillis() > state.start + brief.budget().timeoutMs()) {
      trace.emit(AGENT_BUDGET_EXCEEDED, Map.of("agent", brief.role().id(), "limit", "time"));
      return Optional.of(
          terminal(
              state,
              Termination.BUDGET_EXCEEDED,
              null,
              List.of(),
              "时间硬限（timeoutMs=" + brief.budget().timeoutMs() + "）",
              state.lastModel,
              degradations));
    }
    return Optional.empty();
  }

  /**
   * 响应入账与响应级检查（原循环中段）：先合并本次响应 tokens 进 {@code state}，再按原顺序——UNAVAILABLE 检查 （终态 model 显式 null、tokens
   * 为合并后累计值，与原 "原累计+本次响应" 完全一致）→ 降级链声明 → model 更新 → 输出累计预算检查。
   *
   * @param brief 自包含简报（输出预算上限与角色 id 来源）
   * @param response 本轮 LLM 响应
   * @param state 循环状态（tokens/lastModel 在此更新）
   * @param trace trace 写入器（输出超限先 emit 再收敛终态）
   * @param degradations 降级事件（UNAVAILABLE 轮不做任何追加，透传给终态）
   * @param <T> 最终输出类型
   * @return 命中 UNAVAILABLE 或输出超预算时的终态；否则 empty 并保证 tokens/lastModel 已入账
   */
  private static <T> Optional<AgentRun<T>> absorbResponse(
      AgentBrief brief,
      LlmResponse response,
      LoopState state,
      TraceWriter trace,
      List<String> degradations) {
    state.tokensIn += response.tokensIn();
    state.tokensOut += response.tokensOut();
    if (response.status() == LlmStatus.UNAVAILABLE) {
      return Optional.of(
          terminal(
              state,
              Termination.LLM_UNAVAILABLE,
              null,
              List.of(),
              response.errorMessage(),
              null,
              degradations));
    }
    if (response.degradation() != null && !response.degradation().isBlank()) {
      // 模型降级链触发（02§4.2/§6）：必须进 stats/Summary，不能只存在于响应对象里
      degradations.add(response.degradation());
    }
    if (response.model() != null) {
      state.lastModel = response.model();
    }
    // 输出预算（03§6 输出预算/代理，累计口径）
    if (state.tokensOut > brief.budget().maxOutputTokens()) {
      trace.emit(
          AGENT_BUDGET_EXCEEDED, Map.of("agent", brief.role().id(), "limit", "output-tokens"));
      return Optional.of(
          terminal(
              state,
              Termination.BUDGET_EXCEEDED,
              null,
              List.of(),
              "输出超预算（累计 " + state.tokensOut + " > " + brief.budget().maxOutputTokens() + "）",
              state.lastModel,
              degradations));
    }
    return Optional.empty();
  }

  /**
   * 工具调用轮处理（协议：prompts/shared/tool-protocol.md）：非工具调用输出原样放行；工具调用先 emit {@code tool_call} （在 allowed
   * 检查之前，顺序不变），越界 → VIOLATION 终态，放行 → emit {@code tool_result} 并以 UNTRUSTED 定界追加对话。
   *
   * @param root 本轮回复的 JSON 树（可能为 null 或非对象）
   * @param brief 自包含简报（角色 id 供 trace）
   * @param toolSet 白名单/作用域工具集
   * @param state 循环状态（轮数与对话在此更新）
   * @param trace trace 写入器
   * @param degradations 降级事件（透传给终态）
   * @param <T> 最终输出类型
   * @return 轮类型 + VIOLATION 时的 TOOL_VIOLATION 终态（其余为 null）
   */
  private static <T> ToolTurn<T> handleToolTurn(
      JsonNode root,
      AgentBrief brief,
      ScopedToolSet toolSet,
      LoopState state,
      TraceWriter trace,
      List<String> degradations) {
    if (root == null || !root.isObject() || !root.hasNonNull("tool")) {
      return new ToolTurn<>(ToolTurn.Kind.NOT_A_TOOL_CALL, null);
    }
    String toolName = root.path("tool").asText();
    ScopedToolSet.ToolResult result = toolSet.execute(toolName, root);
    trace.emit(
        TOOL_CALL,
        Map.of(
            "agent",
            brief.role().id(),
            "tool",
            toolName,
            "turn",
            state.turns,
            "allowed",
            result.allowed()));
    if (!result.allowed()) {
      // 白名单/作用域违规 = 隔离边界被试探：立即终止该代理（03§2.3）
      return new ToolTurn<>(
          ToolTurn.Kind.VIOLATION,
          terminal(
              state,
              Termination.TOOL_VIOLATION,
              null,
              List.of(),
              "工具越界: " + toolName,
              state.lastModel,
              degradations));
    }
    trace.emit(
        TOOL_RESULT,
        Map.of(
            "agent", brief.role().id(),
            "tool", toolName,
            "chars", result.output().length()));
    state
        .conversation
        .append("\n\n## 工具结果（第 ")
        .append(state.turns)
        .append(" 轮，工具 ")
        .append(toolName)
        .append("）\n")
        .append(TemplateSupport.untrusted(result.output()));
    return new ToolTurn<>(ToolTurn.Kind.CONTINUE, null);
  }

  /**
   * 最终输出解析收敛（04§6：失败携错误重试 ≤2，不放宽 schema）：errors 空 → COMPLETED；重试耗尽 → SCHEMA_VIOLATION（errors
   * 原样透出）；否则计一次重试、追加降级声明与系统重试提示后 RETRY（对话在 {@code state} 上原样追加）。
   *
   * @param parsed 角色解析器结果（可能含逐条校验错误）
   * @param degradations 降级事件（RETRY 时追加 {@code schema_retry_N}）
   * @param state 循环状态（schemaRetries 与对话在此更新）
   * @param <T> 最终输出类型
   * @return 轮类型 + COMPLETED/EXHAUSTED 时的终态（RETRY 为 null）
   */
  private static <T> FinalStep<T> finalizeOutput(
      FinalOutputParser.Parsed<T> parsed, List<String> degradations, LoopState state) {
    if (parsed.errors().isEmpty()) {
      return new FinalStep<>(
          FinalStep.Kind.COMPLETED,
          terminal(
              state,
              Termination.COMPLETED,
              parsed.value(),
              List.of(),
              null,
              state.lastModel,
              degradations));
    }
    if (state.schemaRetries >= MAX_SCHEMA_RETRIES) {
      return new FinalStep<>(
          FinalStep.Kind.EXHAUSTED,
          terminal(
              state,
              Termination.SCHEMA_VIOLATION,
              null,
              parsed.errors(),
              "schema 重试耗尽",
              state.lastModel,
              degradations));
    }
    state.schemaRetries++;
    degradations.add("schema_retry_" + state.schemaRetries);
    state
        .conversation
        .append("\n\n【系统提示】上次输出未通过 schema 校验，逐条错误如下：\n")
        .append(String.join("\n", parsed.errors()))
        .append("\n请严格按输出契约重新输出，只输出一个 JSON 对象。");
    return new FinalStep<>(FinalStep.Kind.RETRY, null);
  }

  private static JsonNode readJsonOrNull(String content) {
    if (content == null || content.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readTree(content);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * 工具调用轮的处置结果（{@link AgentRunner#handleToolTurn} 产出，替代原工具分支内的 continue/return）。
   *
   * @param kind 轮类型
   * @param terminal VIOLATION 时的 TOOL_VIOLATION 终态（NOT_A_TOOL_CALL/CONTINUE 为 null）
   * @param <T> 最终输出类型
   */
  private record ToolTurn<T>(Kind kind, AgentRun<T> terminal) {

    /** 轮类型。 */
    enum Kind {
      /** 回复不是工具调用 → 进入最终输出解析 */
      NOT_A_TOOL_CALL,
      /** 工具执行成功且对话已追加结果 → 继续下一轮 */
      CONTINUE,
      /** 白名单/作用域越界 → 立即以 TOOL_VIOLATION 终止 */
      VIOLATION
    }
  }

  /**
   * 最终输出的解析收敛结果（{@link AgentRunner#finalizeOutput} 产出，替代原解析分支内的 return/落盘重试）。
   *
   * @param kind 收敛类型
   * @param terminal COMPLETED/EXHAUSTED 时的终态（RETRY 为 null）
   * @param <T> 最终输出类型
   */
  private record FinalStep<T>(Kind kind, AgentRun<T> terminal) {

    /** 收敛类型。 */
    enum Kind {
      /** 校验通过 → 正常完成 */
      COMPLETED,
      /** 重试预算已追加、对话已追加系统提示 → 回到循环头再试 */
      RETRY,
      /** 重试耗尽 → 以 SCHEMA_VIOLATION 终止 */
      EXHAUSTED
    }
  }
}
