package org.cra.llm.api;

import java.util.Objects;

/**
 * 网关配置（05§5 cra.llm 的 M0 子集；值来源 = 配置文件与环境变量，由组合根装配）。
 *
 * @param baseUrl OpenAI 兼容端点根地址（如 https://api.example.com/v1）
 * @param model 模型名
 * @param apiKey API 密钥（只从环境变量读，05§6）
 * @param maxConcurrency 并发信号量上限（默认 8，02§4.5）
 * @param maxRetries 429/5xx/超时最大重试次数（默认 3，02§4.5）
 * @param queueTimeoutMs 信号量排队超时（超时降级，02§4.5）
 * @param requestTimeoutMs 单次请求超时
 * @param backoffBaseMs 指数退避基值（第 n 次重试等待 base * 2^(n-1)，封顶 backoffMaxMs）
 * @param backoffMaxMs 退避封顶
 */
public record LlmGatewayConfig(
    String baseUrl,
    String model,
    String apiKey,
    int maxConcurrency,
    int maxRetries,
    long queueTimeoutMs,
    long requestTimeoutMs,
    long backoffBaseMs,
    long backoffMaxMs) {

  /** 并发上限默认值（02§4.5）。 */
  public static final int DEFAULT_MAX_CONCURRENCY = 8;

  /** 重试次数默认值（02§4.5：最多 3 次）。 */
  public static final int DEFAULT_MAX_RETRIES = 3;

  public LlmGatewayConfig {
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(apiKey, "apiKey");
    if (baseUrl.isBlank() || model.isBlank()) {
      throw new IllegalArgumentException("baseUrl/model must be non-blank");
    }
    if (maxConcurrency <= 0 || maxRetries < 0) {
      throw new IllegalArgumentException("maxConcurrency/maxRetries must be positive");
    }
    if (queueTimeoutMs <= 0
        || requestTimeoutMs <= 0
        || backoffBaseMs <= 0
        || backoffMaxMs < backoffBaseMs) {
      throw new IllegalArgumentException("timeouts must be positive and backoff capped");
    }
  }

  /**
   * 生产默认参数构造。
   *
   * @param baseUrl 端点根地址
   * @param model 模型名
   * @param apiKey API 密钥
   * @return 默认并发/重试/超时参数的配置
   */
  public static LlmGatewayConfig ofProduction(String baseUrl, String model, String apiKey) {
    return new LlmGatewayConfig(
        baseUrl,
        model,
        apiKey,
        DEFAULT_MAX_CONCURRENCY,
        DEFAULT_MAX_RETRIES,
        30_000,
        120_000,
        1_000,
        8_000);
  }
}
