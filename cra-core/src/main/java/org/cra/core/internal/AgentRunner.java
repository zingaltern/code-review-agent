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
 * 子代理执行器（02§3 *Agent 语义的 M0 形态：LLM 循环 + 结构化输出；M0 无工具循环—— 上下文全部内联，M2 T2.1 引入受限工具）。schema 校验失败携错误重试
 * ≤2 次，仍失败丢弃记 SCHEMA_VIOLATION（04§6）；LLM 不可得如实上告，由 run 收尾判 FAILED（02§4.2）。
 */
final class AgentRunner {

  /** schema 重试上限（04§6：≤2 次）。 */
  static final int MAX_SCHEMA_RETRIES = 2;

  private final LlmGateway gateway;

  AgentRunner(LlmGateway gateway) {
    this.gateway = gateway;
  }

  /** 单代理执行结果。 */
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

  /**
   * 执行一次代理调用（含 schema 重试）。
   *
   * @param brief 自包含简报
   * @param runId run 标识
   * @return 执行结果
   */
  AgentRun execute(AgentBrief brief, String runId) {
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
        return new AgentRun(
            List.of(),
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
      FindingJsonCodec.ParseResult parsed =
          FindingJsonCodec.parse(response.content(), runId, brief.role().id(), response.model());
      if (parsed.errors().isEmpty()) {
        return new AgentRun(
            parsed.findings(),
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
              + "\n请严格按输出契约重新输出，只输出一个 JSON 对象。";
    }
    return new AgentRun(
        List.of(),
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
