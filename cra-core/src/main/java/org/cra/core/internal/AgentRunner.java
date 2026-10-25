package org.cra.core.internal;

import java.util.ArrayList;
import java.util.List;
import org.cra.agents.api.AgentBrief;
import org.cra.common.finding.CandidateFinding;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.ResponseMode;

/**
 * 子代理执行器（02§3 *Agent 语义：LLM 循环 + 结构化输出；上下文全部内联，M2 无工具循环——判定补查由 编排器受控注入）。schema 校验失败携错误重试 ≤2
 * 次，仍失败丢弃记 SCHEMA_VIOLATION（04§6）；LLM 不可得 如实上告，由调用方降级（02§4.2）。判定/终裁输出经 {@link #executeParsed}
 * 走同一重试骨架（T2.4/T2.6）。
 */
final class AgentRunner {

  /** schema 重试上限（04§6：≤2 次）。 */
  static final int MAX_SCHEMA_RETRIES = 2;

  private final LlmGateway gateway;

  AgentRunner(LlmGateway gateway) {
    this.gateway = gateway;
  }

  /** 单代理执行结果（findings 形态，评审波用）。 */
  record AgentRun(
      List<CandidateFinding> findings,
      List<String> schemaErrors,
      long tokensIn,
      long tokensOut,
      long latencyMs,
      boolean llmUnavailable,
      String llmError,
      String model,
      List<String> degradations) {}

  /** 泛化执行结果（value 形态由解析器决定）。 */
  record AgentRunT<T>(
      T value,
      List<String> schemaErrors,
      long tokensIn,
      long tokensOut,
      long latencyMs,
      boolean llmUnavailable,
      String llmError,
      String model,
      List<String> degradations) {}

  /** 解析器出口（value + 逐条校验错误，04§6 携错误重试的载体）。 */
  record Parse<T>(T value, List<String> errors) {}

  /** 输出解析器（接收 json 与产出模型名——origin 归因，05§1.1）。 */
  interface ResponseParser<T> {
    Parse<T> parse(String json, String model);
  }

  /**
   * 执行一次评审代理调用（含 schema 重试）。
   *
   * @param brief 自包含简报
   * @param runId run 标识
   * @return 执行结果
   */
  AgentRun execute(AgentBrief brief, String runId) {
    AgentRunT<List<CandidateFinding>> run =
        executeParsed(
            brief,
            runId,
            (json, model) -> {
              FindingJsonCodec.ParseResult parsed =
                  FindingJsonCodec.parse(json, runId, brief.role().id(), model);
              return new Parse<>(parsed.findings(), parsed.errors());
            });
    return new AgentRun(
        run.value() == null ? List.of() : run.value(),
        run.schemaErrors(),
        run.tokensIn(),
        run.tokensOut(),
        run.latencyMs(),
        run.llmUnavailable(),
        run.llmError(),
        run.model(),
        run.degradations());
  }

  /**
   * 执行一次代理调用并按给定解析器结构化（验证/终裁共用重试骨架）。
   *
   * @param brief 自包含简报
   * @param runId run 标识
   * @param parser 输出解析器（value + 逐条错误；错误非空触发重试）
   * @param <T> 解析产物类型
   * @return 执行结果
   */
  <T> AgentRunT<T> executeParsed(AgentBrief brief, String runId, ResponseParser<T> parser) {
    long tokensIn = 0;
    long tokensOut = 0;
    long start = System.currentTimeMillis();
    List<String> degradations = new ArrayList<>();
    String userPrompt = brief.userPrompt();
    for (int attempt = 0; attempt <= MAX_SCHEMA_RETRIES; attempt++) {
      LlmResponse response =
          gateway.complete(
              new LlmRequest(
                  brief.systemPrompt(),
                  userPrompt,
                  ResponseMode.JSON,
                  (int) brief.budget().maxOutputTokens(),
                  brief.role().id()));
      if (response.status() == org.cra.llm.api.LlmStatus.UNAVAILABLE) {
        return new AgentRunT<>(
            null,
            List.of(),
            tokensIn + response.tokensIn(),
            tokensOut,
            System.currentTimeMillis() - start,
            true,
            response.errorMessage(),
            null,
            degradations);
      }
      tokensIn += response.tokensIn();
      tokensOut += response.tokensOut();
      Parse<T> parsed = parser.parse(response.content(), response.model());
      if (parsed.errors().isEmpty()) {
        return new AgentRunT<>(
            parsed.value(),
            List.of(),
            tokensIn,
            tokensOut,
            System.currentTimeMillis() - start,
            false,
            null,
            response.model(),
            degradations);
      }
      degradations.add("schema_retry_" + attempt);
      // 04§6：携校验错误重试，不放宽 schema、不正则修 JSON
      userPrompt =
          brief.userPrompt()
              + "\n\n【系统提示】上次输出未通过 schema 校验，逐条错误如下：\n"
              + String.join("\n", parsed.errors())
              + "\n请严格按输出契约重新输出，只输出一个 json 对象。";
    }
    return new AgentRunT<>(
        null,
        List.of("SCHEMA_VIOLATION: " + MAX_SCHEMA_RETRIES + " 次重试后仍不合法"),
        tokensIn,
        tokensOut,
        System.currentTimeMillis() - start,
        false,
        null,
        null,
        degradations);
  }
}
