package org.cra.llm.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.cra.common.text.Texts;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;
import org.cra.llm.api.ModelTier;

/**
 * 网关默认实现（T2.9 路由形态，02§4.5）：per-model RPM 滑窗限流 → 全局并发信号量（上限 8）→ 重试循环（429/5xx/超时指数退避 ≤maxRetries，其余
 * 4xx 不重试）→ 降级链（请求档位失败后按 fallback-chain 换端点重走，02§4.2； 全链耗尽返回 UNAVAILABLE，底层异常绝不抛穿编排层，
 * 09§4）。中断终止整条链（线程在停，不再换端点）。共享可变状态仅限 RpmLimiter 与本类私有字段（09§6）。
 */
public final class DefaultLlmGateway implements LlmGateway {

  private final LlmGatewayConfig config;
  private final LlmTransport transport;
  private final LongConsumer sleeper;
  private final Semaphore concurrency;
  private final BackoffPolicy backoff;
  private final RpmLimiter rpmLimiter;

  public DefaultLlmGateway(LlmGatewayConfig config, LlmTransport transport, LongConsumer sleeper) {
    this(config, transport, sleeper, System::currentTimeMillis);
  }

  DefaultLlmGateway(
      LlmGatewayConfig config,
      LlmTransport transport,
      LongConsumer sleeper,
      LongSupplier clockMillis) {
    this.config = config;
    this.transport = transport;
    this.sleeper = sleeper;
    this.concurrency = new Semaphore(config.maxConcurrency());
    this.backoff = new BackoffPolicy(config.backoffBaseMs(), config.backoffMaxMs());
    this.rpmLimiter = new RpmLimiter(clockMillis, sleeper);
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long start = System.currentTimeMillis();
    List<ModelTier> sequence = attemptSequence(request.tier());
    List<String> attemptFailures = new ArrayList<>();
    try {
      for (int index = 0; index < sequence.size(); index++) {
        ModelTier tier = sequence.get(index);
        LlmGatewayConfig.ModelEndpoint endpoint = config.models().get(tier);
        LlmResponse response = attempt(endpoint, request, tier, attemptFailures);
        if (response.status() == LlmStatus.OK) {
          if (index == 0) {
            return response;
          }
          String degradation =
              "model-fallback:"
                  + request.tier().name().toLowerCase(Locale.ROOT)
                  + "→"
                  + tier.name().toLowerCase(Locale.ROOT);
          return LlmResponse.ok(
              response.content(),
              response.tokensIn(),
              response.tokensOut(),
              response.latencyMs(),
              response.model(),
              degradation);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return LlmResponse.unavailable(
          "interrupted during llm call", 0, System.currentTimeMillis() - start);
    }
    return LlmResponse.unavailable(
        "llm unavailable on all endpoints: " + String.join("; ", attemptFailures),
        0,
        System.currentTimeMillis() - start);
  }

  /**
   * 尝试序列（02§4.2 降级链）：请求档位先试，fallback-chain 中其余档位依序兜底（去重保序）。 链条由配置保证只含已配置端点（LlmGatewayConfig 校验）。
   */
  private List<ModelTier> attemptSequence(ModelTier requested) {
    Set<ModelTier> sequence = new LinkedHashSet<>();
    sequence.add(requested);
    sequence.addAll(config.fallbackChain());
    return List.copyOf(sequence);
  }

  /**
   * 单端点尝试：RPM 取槽 → 信号量排队（超时该端点失败，试链上下一端点，02§4.5）→ 重试循环。 非中断失败原因追加进 attemptFailures。
   *
   * @throws InterruptedException RPM 排队/信号量排队/退避睡眠被中断（上抛终止整条链）
   */
  private LlmResponse attempt(
      LlmGatewayConfig.ModelEndpoint endpoint,
      LlmRequest request,
      ModelTier tier,
      List<String> attemptFailures)
      throws InterruptedException {
    long start = System.currentTimeMillis();
    rpmLimiter.awaitSlot(tier, config.rpmOf(tier));
    boolean acquired = false;
    try {
      acquired = concurrency.tryAcquire(config.queueTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        // 排队超时按降级语义收尾，并试降级链下一端点（02§4.5）
        attemptFailures.add(tierName(tier) + ": concurrency queue timeout");
        return LlmResponse.unavailable(
            tierName(tier) + ": concurrency queue timeout", 0, System.currentTimeMillis() - start);
      }
      return retryLoop(endpoint, request, start, tier, attemptFailures);
    } finally {
      if (acquired) {
        concurrency.release();
      }
    }
  }

  private LlmResponse retryLoop(
      LlmGatewayConfig.ModelEndpoint endpoint,
      LlmRequest request,
      long start,
      ModelTier tier,
      List<String> attemptFailures)
      throws InterruptedException {
    String url = endpoint.baseUrl().replaceAll("/+$", "") + "/chat/completions";
    String body = OpenAiCodec.encodeRequest(endpoint.model(), request, config.temperature());
    for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
      if (attempt > 0) {
        sleeper.accept(backoff.delayForAttempt(attempt));
      }
      try {
        LlmTransport.TransportResponse response =
            transport.post(
                url,
                endpoint.apiKey(),
                body,
                java.time.Duration.ofMillis(config.requestTimeoutMs()));
        if (response.statusCode() == 200) {
          return parseOk(response.body(), start);
        }
        if (isRetryable(response.statusCode())) {
          continue; // 429/5xx：指数退避后重试（02§4.5）
        }
        String failure =
            tierName(tier)
                + ": llm http "
                + response.statusCode()
                + ": "
                + Texts.truncate(response.body(), 200);
        attemptFailures.add(failure);
        return LlmResponse.unavailable(failure, 0, System.currentTimeMillis() - start);
      } catch (java.io.IOException e) {
        // 网络/超时：与 429/5xx 同类可重试故障；耗尽后换降级链下一端点（09§4 边界转换）
        if (attempt == config.maxRetries()) {
          String failure =
              tierName(tier) + ": llm transport failed after retries: " + e.getMessage();
          attemptFailures.add(failure);
          return LlmResponse.unavailable(failure, 0, System.currentTimeMillis() - start);
        }
      }
    }
    String failure = tierName(tier) + ": llm retries exhausted (" + config.maxRetries() + ")";
    attemptFailures.add(failure);
    return LlmResponse.unavailable(failure, 0, System.currentTimeMillis() - start);
  }

  private LlmResponse parseOk(String body, long start) {
    try {
      OpenAiCodec.ParsedResponse parsed = OpenAiCodec.decodeResponse(body);
      if (parsed.content() == null) {
        return LlmResponse.unavailable(
            "llm response missing content", 0, System.currentTimeMillis() - start);
      }
      return LlmResponse.ok(
          parsed.content(),
          parsed.tokensIn(),
          parsed.tokensOut(),
          System.currentTimeMillis() - start,
          parsed.model());
    } catch (java.io.IOException e) {
      // 协议级坏响应不重试（重试无法修复），直接降级并说明
      return LlmResponse.unavailable(
          "llm response unparseable: " + e.getMessage(), 0, System.currentTimeMillis() - start);
    }
  }

  private static String tierName(ModelTier tier) {
    return tier.name().toLowerCase(Locale.ROOT);
  }

  private static boolean isRetryable(int statusCode) {
    return statusCode == 429 || statusCode >= 500;
  }
}
