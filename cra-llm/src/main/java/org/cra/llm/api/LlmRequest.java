package org.cra.llm.api;

/**
 * 一次 LLM 调用请求。模型由网关按 {@code tier} 路由解析（02§4.5：请求档位先试， fallback-chain 依序兜底；T2.9）。
 *
 * @param systemPrompt 系统提示词
 * @param userPrompt 用户内容（brief 渲染结果）
 * @param mode 响应模式（TEXT/JSON）
 * @param maxOutputTokens 输出 token 上限，null 表示不传该字段
 * @param caller 调用方标识（trace/日志归属，与 AgentRole 一致）
 * @param tier 模型档位路由意图（null 视为 standard——M0 形态调用方零改动）
 */
public record LlmRequest(
    String systemPrompt,
    String userPrompt,
    ResponseMode mode,
    Integer maxOutputTokens,
    String caller,
    ModelTier tier) {

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
    tier = tier == null ? ModelTier.STANDARD : tier;
  }

  /**
   * M0 形态兼容构造（standard 档）。
   *
   * @param systemPrompt 系统提示词
   * @param userPrompt 用户内容
   * @param mode 响应模式
   * @param maxOutputTokens 输出 token 上限
   * @param caller 调用方标识
   */
  public LlmRequest(
      String systemPrompt,
      String userPrompt,
      ResponseMode mode,
      Integer maxOutputTokens,
      String caller) {
    this(systemPrompt, userPrompt, mode, maxOutputTokens, caller, ModelTier.STANDARD);
  }
}
