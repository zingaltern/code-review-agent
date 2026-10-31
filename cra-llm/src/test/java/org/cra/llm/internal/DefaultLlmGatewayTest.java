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
    private final List<String> urls = new ArrayList<>();
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
      urls.add(url);
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
        if (next instanceof LlmTransport.TransportResponse raw) {
          return raw; // 自定义响应体（坏 JSON 等）
        }
        throw (IOException) next;
      } finally {
        inFlight.decrementAndGet();
      }
    }
  }

  private static LlmGatewayConfig.ModelEndpoint endpoint(String model) {
    return endpointAt("http://llm.test/v1/", model);
  }

  private static LlmGatewayConfig.ModelEndpoint endpointAt(String baseUrl, String model) {
    return new LlmGatewayConfig.ModelEndpoint(model, baseUrl, "test-key");
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
        java.util.Map.of(),
        null);
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
        java.util.Map.of(),
        null);
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
    // usage(100) + 1 次 5xx 尝试的输入估算（429 被拒未计费不计；02§4.4 计量不静默少计）
    assertThat(response.tokensIn()).isEqualTo(100 + TokenEstimator.estimate("sysuser"));
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
  void queueTimeoutDoesNotConsumeRpmSlot() throws Exception {
    // 并发排队超时的请求未触达模型，不得占用预算内的 RPM 配额（02§4.5 "实际请求"口径）
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    CountDownLatch gate = new CountDownLatch(1);
    transport.holdUntil(gate);
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 50, java.util.Map.of(ModelTier.STANDARD, 2, ModelTier.LIGHT, 60)),
            transport,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            },
            clock::get);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> gateway.complete(request(ModelTier.STANDARD)));
      // 让首个请求进入传输层并占住并发许可
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (transport.inFlight.get() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(transport.inFlight.get()).isEqualTo(1);
      LlmResponse queued = gateway.complete(request(ModelTier.STANDARD));
      assertThat(queued.status()).isEqualTo(LlmStatus.UNAVAILABLE);
      assertThat(queued.errorMessage()).contains("queue timeout");
      gate.countDown();
      assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(LlmStatus.OK);
      // 排队超时未消耗第 2 个 RPM 槽：后续请求应立即放行（若被消耗会等待 60s）
      assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
      assertThat(waits).isEmpty();
    } finally {
      gate.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void baseUrlTrailingSlashHandled() throws Exception {
    ScriptedTransport transport = new ScriptedTransport(200);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    gateway.complete(request());
    // 尾斜杠归一 + /chat/completions 拼接：URL 真实传给传输层
    assertThat(transport.urls).containsExactly("http://llm.test/v1/chat/completions");
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
    // RPM=2：前两请求立即放行，第三请求等待最早时间戳出窗（测试注入时钟前进，无真实睡眠；
    // queueTimeout 须容纳 60s 出窗等待——等待受 queueTimeout 约束是 RPM 排队的既定语义）
    ScriptedTransport transport = new ScriptedTransport(200, 200, 200);
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 120_000, java.util.Map.of(ModelTier.STANDARD, 2, ModelTier.LIGHT, 60)),
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

  // ---------- 计量与档位回落（02§4.4 / 09§4） ----------

  @Test
  void fiveHundredAttemptsCountTowardTokensOnSuccess() {
    // 5xx：服务端已处理请求后失败，输入 token 确定消耗，成功结果合并 usage 与失败尝试估算
    ScriptedTransport transport = new ScriptedTransport(500, 200);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(response.tokensIn()).isEqualTo(100 + TokenEstimator.estimate("sysuser"));
  }

  @Test
  void allEndpointsExhaustedCarriesConsumedInputEstimate() {
    // 全链耗尽：两个端点各 4 次 5xx，输入估算全部并入 UNAVAILABLE（不再恒 0）
    ScriptedTransport transport = new ScriptedTransport(503, 503, 503, 503, 503, 503, 503, 503);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            routedConfig(1, 1_000, LlmGatewayConfig.defaultRpm()), transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.STANDARD));
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.tokensIn()).isEqualTo(8 * TokenEstimator.estimate("sysuser"));
  }

  @Test
  void unparseableOkResponseCarriesConsumedInput() {
    // 200 但响应不可解析：请求已被服务端处理，输入 token 确定消耗
    ScriptedTransport transport =
        new ScriptedTransport(new LlmTransport.TransportResponse(200, "not-json"));
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.errorMessage()).contains("unparseable");
    assertThat(response.tokensIn()).isEqualTo(TokenEstimator.estimate("sysuser"));
  }

  @Test
  void unconfiguredRequestedTierFallsBackToStandard() {
    // 请求档位未配置端点：回落 standard（配置强制项，05§5），不抛 NPE 穿出网关（09§4）。
    // 请求档位从未尝试，注记用 tier-unconfigured 而非 model-fallback（降级链语义留给真实换端点）
    ScriptedTransport transport = new ScriptedTransport(200);
    LlmGatewayConfig onlyStandard =
        new LlmGatewayConfig(
            java.util.Map.of(ModelTier.STANDARD, endpoint("std-model")),
            List.of(),
            LlmGatewayConfig.defaultRpm(),
            1,
            3,
            1_000,
            500,
            10,
            40,
            null,
            java.util.Map.of(),
            null);
    DefaultLlmGateway gateway = new DefaultLlmGateway(onlyStandard, transport, d -> {});
    LlmResponse response = gateway.complete(request(ModelTier.STRONG));
    assertThat(response.status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies.get(0)).contains("std-model");
    assertThat(response.degradation()).isEqualTo("tier-unconfigured:strong→standard");
  }

  @Test
  void fourHundredAfterFiveHundredKeepsConsumedInput() {
    // 第 0 次尝试 500（已确认消耗），第 1 次尝试 400 不可重试：消耗保留，不因换出口清零
    ScriptedTransport transport = new ScriptedTransport(500, 400);
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.errorMessage()).contains("http 400");
    assertThat(response.tokensIn()).isEqualTo(TokenEstimator.estimate("sysuser"));
  }

  @Test
  void ioFailureAfterFiveHundredKeepsConsumedInput() {
    // IOException 耗尽：本次尝试不可判定计 0，先前 5xx 的已确认消耗保留
    ScriptedTransport transport =
        new ScriptedTransport(500, new IOException("boom"), new IOException("boom"));
    DefaultLlmGateway gateway = new DefaultLlmGateway(config(1, 1_000), transport, d -> {});
    LlmResponse response = gateway.complete(request());
    assertThat(response.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(response.errorMessage()).contains("transport failed");
    assertThat(response.tokensIn()).isEqualTo(TokenEstimator.estimate("sysuser"));
  }

  @Test
  void rpmWindowSharedAcrossTrailingSlashVariants() {
    // 两档 base-url 仅差尾斜杠 = 同一物理端点：RPM 窗口共享（identity 与 URL 拼接同用去尾斜杠形态）
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            new LlmGatewayConfig(
                LlmGatewayConfig.models(
                    endpointAt("http://llm.test/v1/", "std-model"),
                    endpointAt("http://llm.test/v1", "std-model"),
                    endpointAt("http://llm.test/v1", "std-model")),
                List.of(),
                java.util.Map.of(ModelTier.STANDARD, 1, ModelTier.LIGHT, 1),
                1,
                3,
                120_000,
                500,
                10,
                40,
                null,
                java.util.Map.of(),
                null),
            transport,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            },
            clock::get);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    LlmResponse light = gateway.complete(request(ModelTier.LIGHT));
    assertThat(light.status()).isEqualTo(LlmStatus.OK);
    // 同一物理端点窗口已满：等待最早时间戳出窗（若分窗则此等待不存在）
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(60_001L);
    assertThat(transport.bodies).hasSize(2);
  }

  // ---------- RPM 语义（per-model 窗口 + 等待预算，02§4.5） ----------

  @Test
  void rpmWaitTimeoutReturnsUnavailableWithoutCallingTransport() {
    // RPM 等待受 queueTimeout 约束：预算内无槽 → 未触达模型，不占配额、不发请求。
    // 无降级链配置（链兜底属设计行为，此处隔离验证单端点语义）
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    AtomicLong clock = new AtomicLong(0);
    LlmGatewayConfig singleEndpoint =
        new LlmGatewayConfig(
            java.util.Map.of(ModelTier.STANDARD, endpoint("std-model")),
            List.of(),
            java.util.Map.of(ModelTier.STANDARD, 1),
            1,
            3,
            50,
            500,
            10,
            40,
            null,
            java.util.Map.of(),
            null);
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(singleEndpoint, transport, d -> {}, clock::get);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    LlmResponse second = gateway.complete(request(ModelTier.STANDARD));
    assertThat(second.status()).isEqualTo(LlmStatus.UNAVAILABLE);
    assertThat(second.errorMessage()).contains("rpm queue timeout");
    assertThat(transport.bodies).hasSize(1); // 第二次从未触达传输层
    // 未占槽：窗口滑出后同一档位立即放行
    clock.addAndGet(60_000);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    assertThat(transport.bodies).hasSize(2);
  }

  @Test
  void sharedPhysicalEndpointSharesRpmWindow() {
    // 三档同端点（M0 形态）：RPM 窗口按物理端点共享，而非按档位相加（per-model，02§4.5）
    ScriptedTransport transport = new ScriptedTransport(200, 200);
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    DefaultLlmGateway gateway =
        new DefaultLlmGateway(
            new LlmGatewayConfig(
                LlmGatewayConfig.models(
                    endpoint("std-model"), endpoint("std-model"), endpoint("std-model")),
                List.of(),
                java.util.Map.of(ModelTier.STANDARD, 1, ModelTier.LIGHT, 1),
                1,
                3,
                120_000,
                500,
                10,
                40,
                null,
                java.util.Map.of(),
                null),
            transport,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            },
            clock::get);
    assertThat(gateway.complete(request(ModelTier.STANDARD)).status()).isEqualTo(LlmStatus.OK);
    LlmResponse light = gateway.complete(request(ModelTier.LIGHT));
    assertThat(light.status()).isEqualTo(LlmStatus.OK);
    // light 走同一物理端点：共享窗口已满（rpm=1），等待最早时间戳出窗（按档位分窗则此等待不存在）
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(60_001L);
    assertThat(transport.bodies).hasSize(2);
  }
}
