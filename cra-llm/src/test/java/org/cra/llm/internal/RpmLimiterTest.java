package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * RpmLimiter 滑动窗口限流（02§4.5 per-model RPM；T2.9）：窗口内放行、达限等待、出窗恢复、物理端点共享（per-model 而非 per-tier）、等待预算。
 */
class RpmLimiterTest {

  private static RpmLimiter.EndpointIdentity identity(String model) {
    return new RpmLimiter.EndpointIdentity("http://llm.test/v1", model);
  }

  @Test
  void grantsUpToLimitWithinWindow() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.awaitSlot(identity("std-model"), 3, 60_000)).isTrue();
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
    assertThat(limiter.awaitSlot(identity("std-model"), 1, 60_000)).isTrue();
    clock.addAndGet(30_000); // 半分钟后第二次请求：窗口未滑出
    assertThat(limiter.awaitSlot(identity("std-model"), 1, 60_000)).isTrue();
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(30_001L); // 0 + 60000 - 30000 + 1
  }

  @Test
  void windowSlidesAndGrantsAgain() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    assertThat(limiter.awaitSlot(identity("std-model"), 1, 60_000)).isTrue();
    clock.addAndGet(60_000); // 最早时间戳恰好出窗（>= 窗宽即剔除）
    assertThat(limiter.awaitSlot(identity("std-model"), 1, 60_000)).isTrue();
    assertThat(clock.get()).isEqualTo(60_000); // 第二次立即放行
  }

  @Test
  void differentEndpointsAreIndependent() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    assertThat(limiter.awaitSlot(identity("std-model"), 1, 60_000)).isTrue();
    assertThat(limiter.awaitSlot(identity("light-model"), 1, 60_000)).isTrue(); // 不同端点不受占用影响
    assertThat(clock.get()).isEqualTo(0);
  }

  @Test
  void sharedPhysicalEndpointSharesWindowAcrossTiers() throws Exception {
    // 多档位解析到同一物理端点 = 同一配额池：档位换着发起也共用同一窗口（per-model，02§4.5）
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    RpmLimiter limiter =
        new RpmLimiter(
            clock::get,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            });
    RpmLimiter.EndpointIdentity shared = identity("std-model");
    assertThat(limiter.awaitSlot(shared, 1, 60_000)).isTrue();
    clock.addAndGet(30_000);
    assertThat(limiter.awaitSlot(shared, 1, 60_000)).isTrue(); // 换档位发起，窗口已满 → 等待
    assertThat(waits).hasSize(1);
    assertThat(waits.get(0)).isEqualTo(30_001L);
  }

  @Test
  void waitBudgetExceededReturnsFalseWithoutConsumingSlot() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    List<Long> waits = new ArrayList<>();
    RpmLimiter limiter =
        new RpmLimiter(
            clock::get,
            ms -> {
              waits.add(ms);
              clock.addAndGet(ms);
            });
    RpmLimiter.EndpointIdentity shared = identity("std-model");
    assertThat(limiter.awaitSlot(shared, 1, 60_000)).isTrue();
    clock.addAndGet(30_000);
    // 窗口满，预计等待 30001ms > 预算 10000ms：不等待、不占槽
    assertThat(limiter.awaitSlot(shared, 1, 10_000)).isFalse();
    assertThat(waits).isEmpty();
    // 未占槽：出窗后同一端点立即放行
    clock.addAndGet(30_000);
    assertThat(limiter.awaitSlot(shared, 1, 60_000)).isTrue();
    assertThat(waits).isEmpty();
  }

  @Test
  void zeroRpmMeansUnlimited() throws Exception {
    AtomicLong clock = new AtomicLong(0);
    RpmLimiter limiter = new RpmLimiter(clock::get, ms -> clock.addAndGet(ms));
    for (int i = 0; i < 100; i++) {
      assertThat(limiter.awaitSlot(identity("strong-model"), 0, 0)).isTrue();
    }
    assertThat(clock.get()).isEqualTo(0);
  }
}
