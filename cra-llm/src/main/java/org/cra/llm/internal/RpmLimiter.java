package org.cra.llm.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.cra.llm.api.ModelTier;

/**
 * per-model RPM 滑动窗口限流器（02§4.5：每模型 RPM 限制；T2.9）。按档位独立计数， 60 秒滚动窗口内已放行请求数达到 RPM 上限时，阻塞等待最早一次请求
 * 出窗（等待时长上限由调用方的排队超时语义约束——本类只负责"等到有槽位"，不自行放弃）。 时钟与睡眠可注入，单测零真实等待（08§2.2）。
 */
final class RpmLimiter {

  /** 滑动窗口宽度（毫秒）：RPM 语义 = 每分钟请求数。 */
  static final long WINDOW_MS = 60_000L;

  private final Map<ModelTier, Deque<Long>> windows = new EnumMap<>(ModelTier.class);
  private final LongSupplier clockMillis;
  private final Sleeper sleeper;

  /**
   * @param clockMillis 毫秒时钟（测试注入）
   * @param sleeper 等待实现（测试注入；生产为 {@code Thread::sleep}）
   */
  RpmLimiter(LongSupplier clockMillis, Sleeper sleeper) {
    this.clockMillis = clockMillis;
    this.sleeper = sleeper;
  }

  /**
   * 等待并占用一个槽位（占用时间戳 = 放行时刻）。锁只覆盖窗口计算与入队，等待在锁外进行—— 一个档位等待时不得阻塞其它档位取槽（09§6）。
   *
   * @param tier 请求档位
   * @param rpm 该档位 RPM 上限（≤0 表示不限制，立即放行）
   * @throws InterruptedException 等待被中断（调用方按不可得降级，02§4.5）
   */
  void awaitSlot(ModelTier tier, int rpm) throws InterruptedException {
    if (rpm <= 0) {
      return;
    }
    while (true) {
      long waitMs;
      synchronized (this) {
        Deque<Long> window = windows.computeIfAbsent(tier, k -> new ArrayDeque<>());
        long now = clockMillis.getAsLong();
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
          window.pollFirst();
        }
        if (window.size() < rpm) {
          window.addLast(now);
          return;
        }
        waitMs = window.peekFirst() + WINDOW_MS - now + 1;
      }
      sleeper.sleep(waitMs);
    }
  }
}
