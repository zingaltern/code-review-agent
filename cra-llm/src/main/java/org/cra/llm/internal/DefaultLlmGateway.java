package org.cra.llm.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.cra.common.text.Texts;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.LlmStatus;
import org.cra.llm.api.ModelTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关默认实现（T2.9 路由形态，02§4.5）：per-model RPM 滑窗限流（窗口按物理端点建，等待受 queueTimeout 约束）→ 全局并发信号量（上限 8）→
 * 重试循环（429/5xx/超时指数退避 ≤maxRetries，其余 4xx 不重试）→ 降级链（请求档位失败后按 fallback-chain 换端点重走，02§4.2；全链耗尽返回
 * UNAVAILABLE，底层异常绝不抛穿编排层，09§4）。中断终止整条链（线程在停，不再换端点）。
 *
 * <p>token 计量（02§4.4）：成功结果含 usage（缺失按请求文本 UTF-8 字节估算）加网关内已消耗的失败尝试；失败结果携带"服务端已处理" 尝试（5xx、200
 * 坏响应）的输入估算——未触达模型的出口（排队超时/4xx/连接失败）计 0，预算护栏不静默少计。共享可变状态仅限 RpmLimiter 与本类私有字段（09§6）。
 */
public final class DefaultLlmGateway implements LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

  private final LlmGatewayConfig config;
  private final LlmTransport transport;
  private final Sleeper sleeper;
  private final Semaphore concurrency;
  private final BackoffPolicy backoff;
  private final RpmLimiter rpmLimiter;

  public DefaultLlmGateway(LlmGatewayConfig config, LlmTransport transport, Sleeper sleeper) {
    this(config, transport, sleeper, System::currentTimeMillis);
  }

  DefaultLlmGateway(
      LlmGatewayConfig config, LlmTransport transport, Sleeper sleeper, LongSupplier clockMillis) {
    this.config = config;
    this.transport = transport;
    this.sleeper = sleeper;
    this.concurrency = new Semaphore(config.maxConcurrency());
    this.backoff = new BackoffPolicy(config.backoffBaseMs(), config.backoffMaxMs());
    this.rpmLimiter = new RpmLimiter(clockMillis, sleeper);
  }

  @Override
  public Optional<LlmGatewayConfig> config() {
    return Optional.of(config);
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long start = System.currentTimeMillis();
    ModelTier requested = request.tier();
    List<ModelTier> sequence = attemptSequence(requested);
    List<String> attemptFailures = new ArrayList<>();
    long consumed = 0; // 降级链上先前端点已消耗的输入 token（估算），并入最终结果
    boolean requestedAttempted = false; // 请求档位是否真的被尝试过（注记分型依据，不从 config 反推）
    try {
      for (int index = 0; index < sequence.size(); index++) {
        ModelTier tier = sequence.get(index);
        if (tier == requested) {
          requestedAttempted = true;
        }
        if (index > 0) {
          // fallback 链触发（09§5 WARN：降级发生）
          log.warn(
              "llm fallback chain triggered: tier={} after failures={}", tier, attemptFailures);
        }
        LlmResponse response =
            retryLoop(config.models().get(tier), request, start, tier, attemptFailures);
        if (response.status() == LlmStatus.OK) {
          return successResult(response, requested, tier, requestedAttempted, consumed);
        }
        consumed += response.tokensIn();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return LlmResponse.unavailable(
          "interrupted during llm call", consumed, System.currentTimeMillis() - start);
    }
    return LlmResponse.unavailable(
        "llm unavailable on all endpoints: " + String.join("; ", attemptFailures),
        consumed,
        System.currentTimeMillis() - start);
  }

  /**
   * 成功出口：拼装累计 token 与降级注记。
   *
   * @param response 端点响应
   * @param requested 请求档位
   * @param tier 实际成功档位
   * @param requestedAttempted 请求档位是否真的被尝试过
   * @param consumed 降级链上先前端点已消耗的输入 token
   * @return 成功响应
   */
  private static LlmResponse successResult(
      LlmResponse response,
      ModelTier requested,
      ModelTier tier,
      boolean requestedAttempted,
      long consumed) {
    return LlmResponse.ok(
        response.content(),
        response.tokensIn() + consumed,
        response.tokensOut(),
        response.latencyMs(),
        response.model(),
        degradationNote(requested, tier, requestedAttempted));
  }

  /**
   * 降级/回落注记（02§4.2）：语义区分按真实尝试史——请求档位尝试过且失败后换端点 = model-fallback； 从未尝试（未配置回落）=
   * tier-unconfigured（回落而非降级，指标可区分）。
   *
   * @param requested 请求档位
   * @param tier 实际成功档位
   * @param requestedAttempted 请求档位是否真的被尝试过
   * @return 注记（null = 未发生档位切换）
   */
  private static String degradationNote(
      ModelTier requested, ModelTier tier, boolean requestedAttempted) {
    if (tier == requested) {
      return null;
    }
    String kind = requestedAttempted ? "model-fallback:" : "tier-unconfigured:";
    return kind
        + requested.name().toLowerCase(Locale.ROOT)
        + "→"
        + tier.name().toLowerCase(Locale.ROOT);
  }

  /**
   * 尝试序列（02§4.2 降级链）：请求档位先试，fallback-chain 中其余档位依序兜底（去重保序）。链内条目由配置保证已配置（LlmGatewayConfig 校验）；
   * 请求档位未配置端点时回落 standard——standard 必配由 LlmGatewayConfig 紧凑构造器强制（"models 必须至少配置 standard 档"）， 因此
   * sequence 内不含未配置档位，不会取到 null endpoint 抛 NPE 穿出网关（09§4）。
   */
  private List<ModelTier> attemptSequence(ModelTier requested) {
    Set<ModelTier> sequence = new LinkedHashSet<>();
    if (config.models().containsKey(requested)) {
      sequence.add(requested);
    } else {
      sequence.add(ModelTier.STANDARD);
    }
    sequence.addAll(config.fallbackChain());
    return List.copyOf(sequence);
  }

  /**
   * 单端点尝试（02§4.5）：每次实际 HTTP 调用独立的 退避 → 并发许可 → RPM 取槽 → 请求。
   *
   * <p>先取并发许可再取 RPM 槽：并发排队超时意味着请求从未触达模型，不消耗 RPM 配额（02§4.5 per-model RPM 按"实际请求"计）。RPM 等待持有并发许可，
   * 故同样受 queueTimeout 约束——许可被占的最坏时长收敛为 queueTimeout，不再无限等待放大其它档位的排队面。
   *
   * @param start 本次 complete() 调用起点（latencyMs 含排队、重试与整条降级链，02§4.2）
   * @throws InterruptedException 退避/并发排队/RPM 等待被中断（上抛终止整条链）
   */
  private LlmResponse retryLoop(
      LlmGatewayConfig.ModelEndpoint endpoint,
      LlmRequest request,
      long start,
      ModelTier tier,
      List<String> attemptFailures)
      throws InterruptedException {
    // 尾斜杠归一只做一次，URL 拼接与 RPM 物理端点标识共用同一形态（避免仅差尾斜杠的配置分窗）
    String base = endpoint.baseUrl().replaceAll("/++$", "");
    String url = base + "/chat/completions";
    String body =
        OpenAiCodec.encodeRequest(
            endpoint.model(), request, config.temperature(), config.reasoningEffort());
    long estimatedInput = TokenEstimator.estimate(request.systemPrompt() + request.userPrompt());
    long consumed = 0; // 本端点内"服务端已处理"的失败尝试（5xx、坏 200）的输入估算
    for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
      if (attempt > 0) {
        long delay = backoff.delayForAttempt(attempt);
        log.debug("llm retry: tier={} attempt={} delayMs={}", tier, attempt, delay);
        sleeper.sleep(delay);
      }
      boolean acquired = concurrency.tryAcquire(config.queueTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        // 未触达模型：不消耗 RPM 槽（02§4.5 per-model RPM 按实际请求计）；consumed 为本端点先前尝试的已确认消耗
        String failure = tierName(tier) + ": concurrency queue timeout";
        attemptFailures.add(failure);
        return LlmResponse.unavailable(failure, consumed, System.currentTimeMillis() - start);
      }
      PostOutcome outcome;
      try {
        outcome = postOnce(base, url, endpoint, body, tier);
      } finally {
        concurrency.release();
      }
      if (outcome.failure() != null) {
        String failure = outcome.failure();
        if (outcome.retryable() && attempt < config.maxRetries()) {
          continue;
        }
        if (outcome.retryable()) {
          failure = tierName(tier) + ": llm transport failed after retries: " + failure;
        }
        attemptFailures.add(failure);
        return LlmResponse.unavailable(failure, consumed, System.currentTimeMillis() - start);
      }
      LlmTransport.TransportResponse response = outcome.response();
      if (response.statusCode() == 200) {
        return parseOk(response.body(), estimatedInput, start, consumed, attemptFailures);
      }
      if (!isRetryable(response.statusCode())) {
        String failure =
            tierName(tier)
                + ": llm http "
                + response.statusCode()
                + ": "
                + Texts.truncate(response.body(), 200);
        attemptFailures.add(failure);
        // 4xx 本次请求被拒未计费，但先前 5xx 尝试的已确认消耗保留（不因换出口清零）
        return LlmResponse.unavailable(failure, consumed, System.currentTimeMillis() - start);
      }
      if (response.statusCode() >= 500) {
        // 5xx：服务端已接收并处理请求后失败，输入 token 确定消耗（429 被拒未计费，不计）
        consumed += estimatedInput;
      }
    }
    String failure = tierName(tier) + ": llm retries exhausted (" + config.maxRetries() + ")";
    attemptFailures.add(failure);
    return LlmResponse.unavailable(failure, consumed, System.currentTimeMillis() - start);
  }

  /**
   * 单次 HTTP 尝试（RPM 取槽 + 请求），失败以结果对象返回（09§4 边界内收敛，不抛穿编排层）。
   *
   * @param base 归一化端点根地址
   * @param url 完整请求地址
   * @param endpoint 端点配置
   * @param body 请求体
   * @param tier 档位（失败信息前缀）
   * @return 响应或失败结果
   * @throws InterruptedException RPM 等待被中断
   */
  private PostOutcome postOnce(
      String base, String url, LlmGatewayConfig.ModelEndpoint endpoint, String body, ModelTier tier)
      throws InterruptedException {
    try {
      boolean slot =
          rpmLimiter.awaitSlot(
              new RpmLimiter.EndpointIdentity(base, endpoint.model()),
              config.rpmOf(tier),
              config.queueTimeoutMs());
      if (!slot) {
        // RPM 预算内无槽：请求未发出，不占配额（与并发排队超时同语义）
        return PostOutcome.failed(tierName(tier) + ": rpm queue timeout", false);
      }
      return PostOutcome.received(
          transport.post(
              url,
              endpoint.apiKey(),
              body,
              Duration.ofMillis(config.requestTimeoutMs()),
              config.extraHeaders()));
    } catch (IllegalArgumentException e) {
      // 非法 URL/请求头（组装期本应拒绝）：边界内收敛为不可得，不抛穿编排层（09§4）
      return PostOutcome.failed(tierName(tier) + ": llm request invalid: " + e.getMessage(), false);
    } catch (IOException e) {
      // 网络/超时：与 429/5xx 同类可重试故障；耗尽后由调用方换降级链下一端点（09§4 边界转换）。
      // 本次尝试计 0（连接失败与服务端已处理不可区分），但先前 5xx 尝试的已确认消耗保留
      return PostOutcome.failed(e.getMessage(), true);
    }
  }

  /**
   * 单次尝试结果（失败信息 + 是否可重试）。
   *
   * @param response HTTP 响应（失败时为 null）
   * @param failure 失败信息（成功时为 null）
   * @param retryable 失败是否可重试
   */
  private record PostOutcome(
      LlmTransport.TransportResponse response, String failure, boolean retryable) {

    /**
     * 成功结果。
     *
     * @param response HTTP 响应
     * @return 结果
     */
    static PostOutcome received(LlmTransport.TransportResponse response) {
      return new PostOutcome(response, null, false);
    }

    /**
     * 失败结果。
     *
     * @param failure 失败信息
     * @param retryable 是否可重试
     * @return 结果
     */
    static PostOutcome failed(String failure, boolean retryable) {
      return new PostOutcome(null, failure, retryable);
    }
  }

  private LlmResponse parseOk(
      String body, long estimatedInput, long start, long consumed, List<String> attemptFailures) {
    try {
      OpenAiCodec.ParsedResponse parsed = OpenAiCodec.decodeResponse(body, estimatedInput);
      if (parsed.content() == null) {
        // 200 但无内容：请求已被服务端处理，输入 token 确定消耗；原因入 attemptFailures（聚合消息可诊断）
        String failure = "llm response missing content";
        attemptFailures.add(failure);
        return LlmResponse.unavailable(
            failure, consumed + estimatedInput, System.currentTimeMillis() - start);
      }
      return LlmResponse.ok(
          parsed.content(),
          parsed.tokensIn() + consumed,
          parsed.tokensOut(),
          System.currentTimeMillis() - start,
          parsed.model());
    } catch (IOException e) {
      // 协议级坏响应不重试（重试无法修复），直接降级并说明；请求已被服务端处理，输入 token 确定消耗
      String failure = "llm response unparseable: " + e.getMessage();
      attemptFailures.add(failure);
      return LlmResponse.unavailable(
          failure, consumed + estimatedInput, System.currentTimeMillis() - start);
    }
  }

  private static String tierName(ModelTier tier) {
    return tier.name().toLowerCase(Locale.ROOT);
  }

  private static boolean isRetryable(int statusCode) {
    return statusCode == 429 || statusCode >= 500;
  }
}
