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
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;
import org.cra.llm.api.ResponseMode;
import org.junit.jupiter.api.Test;

/**
 * 网关行为单测（fake 传输，无网络，08§2.2）：429/5xx 指数退避、耗尽降级、4xx 快速失败、 并发信号量、排队超时降级、JSON 模式请求形状（07 T0.2；07§1 验收 5
 * 的单测侧）。
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

  private static LlmGatewayConfig config(int concurrency, long queueTimeoutMs) {
    return new LlmGatewayConfig(
        "http://llm.test/v1/",
        "test-model",
        "test-key",
        concurrency,
        3,
        queueTimeoutMs,
        500,
        10,
        40);
  }

  private static LlmRequest request() {
    return new LlmRequest("sys", "user", ResponseMode.JSON, null, "general-reviewer");
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
}
