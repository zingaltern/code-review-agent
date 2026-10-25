package org.cra.llm.internal;

/**
 * 指数退避策略（02§4.5：429/5xx 指数退避，最多 maxRetries 次）。纯函数，可确定性单测 （08§2.2：重试的非网络逻辑单测）；第 n 次重试（n 从 1 起）等待
 * base * 2^(n-1)，封顶 max。
 */
final class BackoffPolicy {

  private final long baseMs;
  private final long maxMs;

  BackoffPolicy(long baseMs, long maxMs) {
    this.baseMs = baseMs;
    this.maxMs = maxMs;
  }

  /**
   * 第 attempt 次重试（1 起）前的等待毫秒数。
   *
   * @param attempt 重试序号（1 起）
   * @return 等待毫秒数（封顶 maxMs）
   */
  long delayForAttempt(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1: " + attempt);
    }
    long delay = baseMs;
    for (int i = 1; i < attempt; i++) {
      delay = Math.min(delay * 2, maxMs);
    }
    return Math.min(delay, maxMs);
  }
}
