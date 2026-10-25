package org.cra.llm.internal;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;

/**
 * 网关默认实现（M0）：并发信号量（02§4.5，上限 8）→ 重试循环（429/5xx/超时指数退避 ≤maxRetries， 其余 4xx 不重试）→ usage
 * 核算。共享可变状态仅限本类内部私有字段（09§6）。
 */
public final class DefaultLlmGateway implements LlmGateway {

  private final LlmGatewayConfig config;
  private final LlmTransport transport;
  private final LongConsumer sleeper;
  private final Semaphore concurrency;
  private final BackoffPolicy backoff;

  public DefaultLlmGateway(LlmGatewayConfig config, LlmTransport transport, LongConsumer sleeper) {
    this.config = config;
    this.transport = transport;
    this.sleeper = sleeper;
    this.concurrency = new Semaphore(config.maxConcurrency());
    this.backoff = new BackoffPolicy(config.backoffBaseMs(), config.backoffMaxMs());
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long start = System.currentTimeMillis();
    String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
    String body = OpenAiCodec.encodeRequest(config.model(), request);
    long queuedTokens =
        TokenEstimator.estimate(request.systemPrompt())
            + TokenEstimator.estimate(request.userPrompt());

    boolean acquired = false;
    try {
      acquired = concurrency.tryAcquire(config.queueTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        // 排队超时按降级语义收尾（02§4.5）
        return LlmResponse.unavailable(
            "llm concurrency queue timeout", 0, System.currentTimeMillis() - start);
      }
      return retryLoop(url, body, queuedTokens, start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return LlmResponse.unavailable(
          "interrupted while waiting for llm slot", 0, System.currentTimeMillis() - start);
    } finally {
      if (acquired) {
        concurrency.release();
      }
    }
  }

  private LlmResponse retryLoop(String url, String body, long queuedTokens, long start)
      throws InterruptedException {
    for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
      if (attempt > 0) {
        sleeper.accept(backoff.delayForAttempt(attempt));
      }
      try {
        LlmTransport.TransportResponse response =
            transport.post(
                url, config.apiKey(), body, java.time.Duration.ofMillis(config.requestTimeoutMs()));
        if (response.statusCode() == 200) {
          return parseOk(response.body(), start);
        }
        if (isRetryable(response.statusCode())) {
          continue; // 429/5xx：指数退避后重试（02§4.5）
        }
        return LlmResponse.unavailable(
            "llm http " + response.statusCode() + ": " + abbreviate(response.body()),
            0,
            System.currentTimeMillis() - start);
      } catch (java.io.IOException e) {
        // 网络/超时：与 429/5xx 同类可重试故障；耗尽后降级返回（09§4 边界转换）
        if (attempt == config.maxRetries()) {
          return LlmResponse.unavailable(
              "llm transport failed after retries: " + e.getMessage(),
              0,
              System.currentTimeMillis() - start);
        }
      }
    }
    return LlmResponse.unavailable(
        "llm retries exhausted (" + config.maxRetries() + ")",
        0,
        System.currentTimeMillis() - start);
  }

  private LlmResponse parseOk(String body, long start) {
    try {
      OpenAiCodec.ParsedResponse parsed = OpenAiCodec.decodeResponse(body);
      if (parsed.content() == null) {
        return LlmResponse.unavailable(
            "llm response missing content", 0, System.currentTimeMillis() - start);
      }
      return new LlmResponse(
          parsed.content(),
          parsed.tokensIn(),
          parsed.tokensOut(),
          System.currentTimeMillis() - start,
          LlmStatus.OK,
          null);
    } catch (java.io.IOException e) {
      // 协议级坏响应不重试（重试无法修复），直接降级并说明
      return LlmResponse.unavailable(
          "llm response unparseable: " + e.getMessage(), 0, System.currentTimeMillis() - start);
    }
  }

  private static boolean isRetryable(int statusCode) {
    return statusCode == 429 || statusCode >= 500;
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}
