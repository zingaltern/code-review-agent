package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.cra.llm.api.ModelTier;
import org.junit.jupiter.api.Test;

/** RpmLimiter 滑动窗口限流（02§4.5 per-model RPM；T2.9）：窗口内放行、达限等待、出窗恢复、分档独立。 */
class RpmLimiterTest {

  @Test
  void grantsUpToLimitWithinWindow() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    for (int i = 0; i < 3; i++) {
      limiter.awaitSlot(ModelTier.STANDARD, 3);
    }
    // 3 次全部立即放行（无等待语义可断言：等待会消费注入时钟，此处时钟未动）
    assertThat(clock.get()).isEqualTo(0);
  }

  @Test
  void blocksAtLimitUntilOldestTimestampLeavesWindow() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    RpmLimiter limiter =
        new RpmLimiter(
            clock::get,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            });
    limiter.awaitSlot(ModelTier.STANDARD, 1);
    clock.addAndGet(30_000); // 半分钟后第二次请求：窗口未滑出
    limiter.awaitSlot(ModelTier.STANDARD, 1);
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(30_001L); // 0 + 60000 - 30000 + 1
  }

  @Test
  void windowSlidesAndGrantsAgain() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    limiter.awaitSlot(ModelTier.STANDARD, 1);
    clock.addAndGet(60_000); // 最早时间戳恰好出窗（>= 窗宽即剔除）
    limiter.awaitSlot(ModelTier.STANDARD, 1);
    assertThat(clock.get()).isEqualTo(60_000); // 第二次立即放行
  }

  @Test
  void tiersAreIndependent() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    limiter.awaitSlot(ModelTier.STANDARD, 1);
    limiter.awaitSlot(ModelTier.LIGHT, 1); // 不同档位不受 standard 占用影响
    assertThat(clock.get()).isEqualTo(0);
  }

  @Test
  void zeroRpmMeansUnlimited() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    for (int i = 0; i < 100; i++) {
      limiter.awaitSlot(ModelTier.STRONG, 0);
    }
    assertThat(clock.get()).isEqualTo(0);
  }
}
