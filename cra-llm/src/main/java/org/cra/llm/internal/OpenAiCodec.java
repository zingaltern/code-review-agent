package org.cra.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.ResponseMode;

/**
 * OpenAI 兼容 chat/completions 编解码（请求体组装 + 响应解析）。 JSON 模式用
 * response_format={"type":"json_object"}（04§6：强约束走 json_schema/function calling， 兼容生态差异大，M0 先锁
 * json_object + 应用层 schema 校验，重试不放宽 schema）。
 */
final class OpenAiCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OpenAiCodec() {}

  static String encodeRequest(
      String model, LlmRequest request, Double temperature, String reasoningEffort) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", model);
    var messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", request.systemPrompt());
    messages.addObject().put("role", "user").put("content", request.userPrompt());
    if (request.mode() == ResponseMode.JSON) {
      root.putObject("response_format").put("type", "json_object");
    }
    if (request.maxOutputTokens() != null) {
      root.put("max_tokens", request.maxOutputTokens());
    }
    if (temperature != null) {
      // 采样温度（05§5 CRA_LLM_TEMPERATURE）：null = 不传，沿用 provider 默认
      root.put("temperature", temperature);
    }
    if (reasoningEffort != null) {
      // 推理力度（v1.3.2 opencode 适配）：none = 关闭 thinking（reasoning token 计入 max_tokens）
      root.put("reasoning_effort", reasoningEffort);
    }
    return root.toString();
  }

  /**
   * 解析 chat/completions 响应（无用量回退估算时使用响应体长度——仅兼容旧调用，输入 token 会偏小）。
   *
   * @param body 响应体
   * @return 解析结果
   * @throws IOException body 非 JSON 或结构不符合协议
   */
  static ParsedResponse decodeResponse(String body) throws IOException {
    return decodeResponse(body, -1);
  }

  /**
   * 解析 chat/completions 响应。
   *
   * @param body 响应体
   * @param estimatedInputTokens usage 缺失时的输入 token 估算值（调用方按**请求**文本估算，≤0 表示回退到响应体估算）
   * @return 解析结果（content + usage；usage 缺失按 字符数/3.5 估算，04§2.1）
   * @throws IOException body 非 JSON 或结构不符合协议
   */
  static ParsedResponse decodeResponse(String body, long estimatedInputTokens) throws IOException {
    JsonNode root = MAPPER.readTree(body);
    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      throw new IOException("no choices in response");
    }
    String content = choices.get(0).path("message").path("content").asText(null);
    JsonNode usage = root.path("usage");
    long tokensIn = usage.path("prompt_tokens").asLong(-1);
    long tokensOut = usage.path("completion_tokens").asLong(-1);
    if (tokensIn < 0) {
      // 输入 token 必须来自请求侧估算：用响应体长度会低估一个数量级，预算护栏随之失真
      tokensIn = estimatedInputTokens > 0 ? estimatedInputTokens : TokenEstimator.estimate(body);
    }
    if (tokensOut < 0) {
      tokensOut = TokenEstimator.estimate(content);
    }
    String model = root.path("model").asText(null);
    return new ParsedResponse(content, tokensIn, tokensOut, model);
  }

  /** 解析结果。 */
  record ParsedResponse(String content, long tokensIn, long tokensOut, String model) {}
}
