package org.cra.llm.api;

import java.util.Objects;

/**
 * 一次 LLM 调用的结果（02§4.2 降级结果对象：UNAVAILABLE 携带原因，不抛异常）。
 *
 * @param content 回复内容（UNAVAILABLE 时为 null）
 * @param tokensIn 输入 token（usage 缺失时按 字符数/3.5 估算，04§2.1）
 * @param tokensOut 输出 token（同上）
 * @param latencyMs 本次调用总耗时（含重试与排队）
 * @param status 结果状态
 * @param errorMessage 失败原因（OK 时为 null）
 * @param model 实际响应的模型名（响应体 model 字段，finding origin.model 用；UNAVAILABLE 为 null）
 * @param degradation 降级注记（T2.9：经降级链换端点成功时形如 {@code model-fallback:standard→light}； 直达成功为
 *     null——编排侧汇入 stats.degradations，02§6 summary 指标行可见）
 */
public record LlmResponse(
    String content,
    long tokensIn,
    long tokensOut,
    long latencyMs,
    LlmStatus status,
    String errorMessage,
    String model,
    String degradation) {

  public LlmResponse {
    Objects.requireNonNull(status, "status");
    if (status == LlmStatus.OK && content == null) {
      throw new IllegalArgumentException("OK response must carry content");
    }
    if (tokensIn < 0 || tokensOut < 0 || latencyMs < 0) {
      throw new IllegalArgumentException("token/latency counters must be non-negative");
    }
  }

  /**
   * 成功结果工厂（M0 形态兼容：无降级注记）。
   *
   * @param content 回复内容
   * @param tokensIn 输入 token
   * @param tokensOut 输出 token
   * @param latencyMs 耗时
   * @param model 模型名
   * @return LlmResponse
   */
  public static LlmResponse ok(
      String content, long tokensIn, long tokensOut, long latencyMs, String model) {
    return ok(content, tokensIn, tokensOut, latencyMs, model, null);
  }

  /**
   * 成功结果工厂（T2.9：携降级注记）。
   *
   * @param content 回复内容
   * @param tokensIn 输入 token
   * @param tokensOut 输出 token
   * @param latencyMs 耗时
   * @param model 模型名
   * @param degradation 降级注记（直达成功为 null）
   * @return LlmResponse
   */
  public static LlmResponse ok(
      String content,
      long tokensIn,
      long tokensOut,
      long latencyMs,
      String model,
      String degradation) {
    return new LlmResponse(
        content, tokensIn, tokensOut, latencyMs, LlmStatus.OK, null, model, degradation);
  }

  /**
   * 降级结果工厂（UNAVAILABLE：content 为 null）。
   *
   * @param errorMessage 失败原因
   * @param tokensIn 已消耗输入 token（可为 0）
   * @param latencyMs 耗时
   * @return LlmResponse
   */
  public static LlmResponse unavailable(String errorMessage, long tokensIn, long latencyMs) {
    return new LlmResponse(
        null, tokensIn, 0, latencyMs, LlmStatus.UNAVAILABLE, errorMessage, null, null);
  }
}
