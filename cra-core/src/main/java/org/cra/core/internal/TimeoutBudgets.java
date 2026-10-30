package org.cra.core.internal;

import java.util.Optional;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;

/**
 * 外层有界等待 backstop（09§6）：未来任务超时不得短于网关最坏重试链。
 *
 * <p>网关单端点最坏耗时 = requestTimeout × (maxRetries+1) + 退避余量；360s 只覆盖单次 120s × 3 重试的常见形态，
 * 配置更长超时/更多重试时会先被外层误杀并把"网关仍在重试"误报成"代理卡死"。有配置时按上式推导，无配置（测试桩/未知实现） 回退 720s。
 */
public final class TimeoutBudgets {

  /** 历史硬限（评审/验证单批等待下限）。 */
  static final long MIN_BACKSTOP_MS = 360_000L;

  /** 无配置回退（覆盖 4×120s + 退避 + 余量）。 */
  static final long FALLBACK_BACKSTOP_MS = 720_000L;

  /** 重试链后的固定余量（brief 落盘/解析/调度）。 */
  static final long MARGIN_MS = 60_000L;

  private TimeoutBudgets() {}

  /**
   * 单次代理/批任务的外层 backstop。
   *
   * @param gateway LLM 网关（可空；配置未知时回退 720s）
   * @return 等待毫秒
   */
  public static long agentBackstopMs(LlmGateway gateway) {
    Optional<LlmGatewayConfig> config = gateway == null ? Optional.empty() : gateway.config();
    if (config.isEmpty()) {
      return FALLBACK_BACKSTOP_MS;
    }
    LlmGatewayConfig c = config.get();
    long attempts = c.maxRetries() + 1L;
    long chain;
    if (c.requestTimeoutMs() > (Long.MAX_VALUE - MARGIN_MS) / attempts) {
      chain = Long.MAX_VALUE;
    } else {
      chain = c.requestTimeoutMs() * attempts + MARGIN_MS;
    }
    return Math.max(MIN_BACKSTOP_MS, chain);
  }

  /**
   * 验证批 backstop：同一网关重试链 + 一次缩上下文重试的余量（验证不可得时 verifyBatch 会整套重跑一次）。
   *
   * @param gateway LLM 网关
   * @return 等待毫秒
   */
  public static long verificationBackstopMs(LlmGateway gateway) {
    long single = agentBackstopMs(gateway);
    return single > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : single * 2;
  }
}
