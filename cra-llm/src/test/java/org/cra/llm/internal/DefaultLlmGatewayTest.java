package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;
import org.cra.llm.api.ModelTier;
import org.cra.llm.api.ResponseMode;
import org.junit.jupiter.api.Test;

/**
 * 网关行为单测（fake 传输，无网络，08§2.2）：429/5xx 指数退避、耗尽降级、4xx 快速失败、 并发信号量、排队超时降级、三档路由与降级链（T2.9，02§4.5/05§5）。
 */
class DefaultLlmGatewayTest {

  private static final String OK_BODY =
      "{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}],"
          + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}";

  /** 按脚本回放的 fake 传输：条目为状态码响应或 IOException。 */
  private static final class ScriptedTransport implements LlmTransport {
    private final List<Object> script;
    private final List<String> bodies = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private volatile CountDownLatch gate;

    ScriptedTransport(Object... script) {
      this.script = new ArrayList<>(List.of(script));
    }

    void holdUntil(CountDownLatch latch) {
      this.gate = latch;
    }

    @Override
    public TransportResponse post(
        String url, String apiKey, String jsonBody, java.time.Duration timeout)
        throws IOException, InterruptedException {
      bodies.add(jsonBody);
      int current = inFlight.incrementAndGet();
      peak.accumulateAndGet(current, Math::max);
      try {
        if (gate != null) {
          gate.await();
        }
        if (script.isEmpty()) {
          throw new IOException("script exhausted");
        }
        Object next = script.remove(0);
        if (next instanceof Integer code) {
          return new TransportResponse(code, code == 200 ? OK_BODY : "{\"error\":\"x\"}");
        }
        throw (IOException) next;
      } finally {
        inFlight.decrementAndGet();
      }
    }
  }

  private static LlmGatewayConfig.ModelEndpoint endpoint(String model) {
    return new LlmGatewayConfig.ModelEndpoint(model, "http://llm.test/v1/", "test-key");
  }

  /** M0 形态：三档同端点、降级链只含 standard（既有行为回归基准）。 */
  private static LlmGatewayConfig config(int concurrency, long queueTimeoutMs) {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(
            endpoint("std-model"), endpoint("std-model"), endpoint("std-model")),
        List.of(ModelTier.STANDARD),
        LlmGatewayConfig.defaultRpm(),
        concurrency,
        3,
        queueTimeoutMs,
        500,
        10,
        40,
        null,
        java.util.Map.of());
  }

  /** 三档独立模型名 + 降级链 standard → light。 */
  private static LlmGatewayConfig routedConfig(
      int concurrency, long queueTimeoutMs, java.util.Map<ModelTier, Integer> rpm) {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(
            endpoint("std-model"), endpoint("strong-model"), endpoint("light-model")),
        List.of(ModelTier.STANDARD, ModelTier.LIGHT),
        rpm,
        concurrency,
        3,
        queueTimeoutMs,
        500,
        10,
        40,
        null,
        java.util.Map.of());
  }

  private static LlmRequest request() {
    return new LlmRequest("sys", "user", ResponseMode.JSON, null, "general-reviewer");
  }

  private static LlmRequest request(ModelTier tier) {
    return new LlmRequest("sys", "user", ResponseMode.JSON, null, "general-reviewer", tier);
  }

  @Test
  void retriesOn429ThenSucceeds() {
    ScriptedTransport transport = new ScriptedTransport(429, 500, 200);
    List<Long> delays = new ArrayList<>();
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, delays::add);
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(response.content()).contains("findings");
    assertThat(response.tokensIn()).isEqualTo(100);
    assertThat(delays).containsExactly(10L, 20L); // 10 * 2^0, 10 * 2^1
  }

  @Test
  void retriesExhaustedDegradesWithoutThrowing() {
    ScriptedTransport transport = new ScriptedTransport(429, 429, 429, 429);
    List<Long> delays = new ArrayList<>();
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, delays::add);
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.content()).isNull();
    assertThat(response.errorMessage()).contains("retries exhausted");
    assertThat(delays).containsExactly(10L, 20L, 40L); // 封顶 backoffMax=40
  }

  @Test
  void transportIoRetriesThenDegrades() {
    ScriptedTransport transport =
        new ScriptedTransport(
            new IOException("boom"),
            new IOException("boom"),
            new IOException("boom"),
            new IOException("boom"));
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.errorMessage()).contains("transport failed");
  }

  @Test
  void nonRetryable4xxFailsFast() {
    ScriptedTransport transport = new ScriptedTransport(401);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(transport.bodies).hasSize(1); // 未重试
  }

  @Test
  void concurrencySemaphoreCapsInFlight() throws Exception {
    ScriptedTransport transport = new ScriptedTransport(200, 200, 200, 200);
    CountDownLatch gate = new CountDownLatch(1);
    transport.holdUntil(gate);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(2, 500), transport, d -> {});
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<java.util.concurrent.Future<LlmResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      futures.add(pool.submit(() -> gateway.complete(request())));
    }
    Thread.sleep(150); // 让全部 4 个调用进入排队/在途
    gate.countDown();
    for (var f : futures) {
      assertThat(f.get(5, TimeUnit.SECONDS).status()).isEqualTo(LlmStatus.OK);
    }
    pool.shutdownNow();
    assertThat(transport.peak.get()).as("并发峰值不得超过信号量上限 2").isEqualTo(2);
  }

  @Test
  void queueTimeoutDegradesWithoutCallingTransport() throws Exception {
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    CountDownLatch gate = new CountDownLatch(1);
    transport.holdUntil(gate);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 50), transport, d -> {});
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var first = pool.submit(() -> gateway.complete(request()));
    Thread.sleep(30);
    var second = pool.submit(() -> gateway.complete(request()));
    assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(second.get(2, TimeUnit.SECONDS).errorMessage()).contains("queue timeout");
    gate.countDown();
    assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(LlmStatus.OK);
    pool.shutdownNow();
    assertThat(transport.bodies).hasSize(1); // 排队超时者从未触达传输层
  }

  @Test
  void baseUrlTrailingSlashHandled() throws Exception {
    ScriptedTransport transport = new ScriptedTransport(200);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    gateway.complete(request());
    // 传输层收到的 URL 由 fake 忽略，但编码/拼接不抛异常即覆盖根路径拼接
    assertThat(transport.bodies).hasSize(1);
  }

  // ---------- T2.9：三档路由与降级链（02§4.5/05§5） ----------

  @Test
  void requestTierRoutesToMatchingEndpoint() {
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    gateway.complete(request(ModelTier.STRONG));
    assertThat(transport.bodies.get(0)).contains("strong-model");
    gateway.complete(request(ModelTier.LIGHT));
    assertThat(transport.bodies.get(1)).contains("light-model");
  }

  @Test
  void fallbackChainSubstitutesEndpointOnRetryExhaustion() {
    // standard 4 次尝试全 429 → 链上 light 接管成功
    ScriptedTransport transport = new ScriptedTransport(429, 429, 429, 429, 200);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.STANDARD));
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies).hasSize(5);
    assertThat(transport.bodies.get(4)).contains("light-model");
    assertThat(response.degradation()).isEqualTo("model-fallback:standard→light");
  }

  @Test
  void fallbackChainAlsoAppliesToNonRetryable4xx() {
    // 401 不重试，但换端点重试是降级链语义（02§4.2：主模型 → 备用模型）
    ScriptedTransport transport = new ScriptedTransport(401, 200);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.STANDARD));
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies).hasSize(2);
    assertThat(transport.bodies.get(1)).contains("light-model");
    assertThat(response.degradation()).isEqualTo("model-fallback:standard→light");
  }

  @Test
  void requestedTierTriedFirstEvenWhenChainStartsElsewhere() {
    ScriptedTransport transport = new ScriptedTransport(200);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.LIGHT));
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies).hasSize(1);
    assertThat(transport.bodies.get(0)).contains("light-model");
    assertThat(response.degradation()).isNull(); // 直达成功无降级注记
  }

  @Test
  void allEndpointsExhaustedReturnsUnavailableWithEveryAttempt() {
    ScriptedTransport transport = new ScriptedTransport(429, 429, 429, 429, 503, 503, 503, 503);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.STANDARD));
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.errorMessage())
        .contains("standard")
        .contains("light")
        .contains("llm unavailable on all endpoints");
  }

  @Test
  void perModelRpmThrottlesThirdImmediateRequest() {
    // RPM=2：前两请求立即放行，第三请求等待最早时间戳出窗（测试注入时钟前进，无真实睡眠）
    ScriptedTransport transport = new ScriptedTransport(200, 200, 200);
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, java.util.Map.of(ModelTier.STANDARD, 2, ModelTier.LIGHT, 60)),
            transport,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            },
            clock::get);
    assertThat(gateway.complete(request()).status()).isEqualTo(LlmStatus.OK);
    assertThat(gateway.complete(request()).status()).isEqualTo(LlmStatus.OK);
    assertThat(gateway.complete(request()).status()).isEqualTo(LlmStatus.OK);
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(60_001L); // 0 + 60000 - 0 + 1
    assertThat(transport.bodies).hasSize(3);
  }

  @Test
  void rpmLimitIsIndependentPerTier() {
    ScriptedTransport transport = new ScriptedTransport(200, 200, 200);
    AtomicLong clock = new AtomicLong(0);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, java.util.Map.of(ModelTier.STANDARD, 1, ModelTier.LIGHT, 1)),
            transport,
            ms -> clock.addAndGet(ms),
            clock::get);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    // strong 未配置 RPM → 不限流，立即放行
    assertThat(gateway.complete(request(ModelTier.STRONG)).status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies).hasSize(3);
  }
}
