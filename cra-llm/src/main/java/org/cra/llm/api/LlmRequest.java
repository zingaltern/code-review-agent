package org.cra.llm.api;

/**
 * 一次 LLM 调用请求（M0 单模型直通：模型由网关配置解析，tier 路由 M2 落地，T2.9）。
 *
 * @param systemPrompt 系统提示词
 * @param userPrompt 用户内容（brief 渲染结果）
 * @param mode 响应模式（TEXT/JSON）
 * @param maxOutputTokens 输出 token 上限，null 表示不传该字段
 * @param caller 调用方标识（trace/日志归属，与 AgentRole 一致）
 */
public record LlmRequest(
    String systemPrompt,
    String userPrompt,
    ResponseMode mode,
    Integer maxOutputTokens,
    String caller) {

  public LlmRequest {
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("systemPrompt must be non-blank");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt must be non-blank");
    }
    mode = mode == null ? ResponseMode.TEXT : mode;
    if (caller == null || caller.isBlank()) {
      throw new IllegalArgumentException("caller must be non-blank");
    }
  }
}
