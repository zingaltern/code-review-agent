package org.cra.llm.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * per-model RPM 滑动窗口限流器（02§4.5：每模型 RPM 限制；T2.9）。窗口按<b>物理端点</b>（base-url + 模型名）建——多档位解析到同一端点时共享同一个
 * 配额池（同一物理模型的厂商配额只有一个，按档位分窗会把各档配额相加，护栏失效）；上限取发起请求档位的 rpm 配置（该档未配置 = 不限）。 60
 * 秒滚动窗口内已放行请求数达上限时，阻塞等待最早一次请求出窗；等待受调用方给定的预算约束（queueTimeout），预算内等不到就 不占槽返回
 * false——请求未发出，与并发排队超时同为"未触达模型"（02§4.5 per-model RPM 按"实际请求"计）。时钟与睡眠可注入，单测零真实等待（08§2.2）。
 */
final class RpmLimiter {

  /** 滑动窗口宽度（毫秒）：RPM 语义 = 每分钟请求数。 */
  static final long WINDOW_MS = 60_000L;

  private final Map<EndpointIdentity, Deque<Long>> windows = new HashMap<>();
  private final LongSupplier clockMillis;
  private final Sleeper sleeper;

  /**
   * 物理端点标识：RPM 配额的实际归属（同一 base-url + 模型名 = 同一厂商配额池）。
   *
   * @param baseUrl 端点根地址（与网关配置一致）
   * @param model 模型名（chat/completions 的 model 字段）
   */
  record EndpointIdentity(String baseUrl, String model) {}

  /**
   * @param clockMillis 毫秒时钟（测试注入）
   * @param sleeper 等待实现（测试注入；生产为 {@code Thread::sleep}）
   */
  RpmLimiter(LongSupplier clockMillis, Sleeper sleeper) {
    this.clockMillis = clockMillis;
    this.sleeper = sleeper;
  }

  /**
   * 等待并占用一个槽位（占用时间戳 = 放行时刻）。锁只覆盖窗口计算与入队，等待在锁外进行——一个端点等待时不得阻塞其它端点取槽（09§6）。
   *
   * @param identity 物理端点（base-url + 模型名）
   * @param rpm 发起档位的 RPM 上限（≤0 表示不限制，立即放行）
   * @param budgetMs 等待预算毫秒（queueTimeout；预计等待超出即放弃，不占槽）
   * @return 是否取得槽位（false = 预算内无槽，未发出请求）
   * @throws InterruptedException 等待被中断（调用方按不可得降级，02§4.5）
   */
  boolean awaitSlot(EndpointIdentity identity, int rpm, long budgetMs) throws InterruptedException {
    if (rpm <= 0) {
      return true;
    }
    long deadline = clockMillis.getAsLong() + budgetMs;
    while (true) {
      long waitMs;
      synchronized (this) {
        Deque<Long> window = windows.computeIfAbsent(identity, k -> new ArrayDeque<>());
        long now = clockMillis.getAsLong();
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
          window.pollFirst();
        }
        if (window.size() < rpm) {
          window.addLast(now);
          return true;
        }
        waitMs = window.peekFirst() + WINDOW_MS - now + 1;
      }
      if (waitMs > deadline - clockMillis.getAsLong()) {
        return false; // 预算内等不到槽：不占槽、不发请求（与并发排队超时同语义）
      }
      sleeper.sleep(waitMs);
    }
  }
}
