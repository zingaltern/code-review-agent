package org.cra.server.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** M4f-full 调度：关闭不采集；开启用配置窗口；重复跑由采集器幂等（这里验证窗口与结果透传）。 */
class EventHistoryCollectionJobTest {

  private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

  @Test
  void disabledJobDoesNotCollect() {
    boolean[] called = {false};
    EventHistoryCollector collector =
        since -> {
          called[0] = true;
          return new EventHistoryCollector.CollectionResult(true, 1, 1, "x");
        };
    EventHistoryCollectionJob job =
        new EventHistoryCollectionJob(false, collector, 7, Clock.fixed(NOW, ZoneOffset.UTC));
    EventHistoryCollector.CollectionResult result = job.runOnce();
    assertThat(result.executed()).isFalse();
    assertThat(result.note()).isEqualTo("disabled");
    assertThat(called[0]).isFalse();
  }

  @Test
  void enabledJobUsesConfiguredLookbackWindow() {
    AtomicInteger calls = new AtomicInteger();
    Instant[] seen = {null};
    EventHistoryCollector collector =
        since -> {
          calls.incrementAndGet();
          seen[0] = since;
          return new EventHistoryCollector.CollectionResult(true, 2, 3, "collected");
        };
    EventHistoryCollectionJob job =
        new EventHistoryCollectionJob(true, collector, 3, Clock.fixed(NOW, ZoneOffset.UTC));
    EventHistoryCollector.CollectionResult result = job.runOnce();
    assertThat(result.executed()).isTrue();
    assertThat(result.tickets()).isEqualTo(2);
    assertThat(result.incidents()).isEqualTo(3);
    assertThat(seen[0]).isEqualTo(NOW.minusSeconds(3L * 24 * 3600));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void failedCollectionIsReportedNotFaked() {
    EventHistoryCollector collector =
        since -> EventHistoryCollector.CollectionResult.failed("来源不可读（重试 3 次）");
    EventHistoryCollectionJob job =
        new EventHistoryCollectionJob(true, collector, 7, Clock.fixed(NOW, ZoneOffset.UTC));
    EventHistoryCollector.CollectionResult result = job.runOnce();
    assertThat(result.executed()).isFalse();
    assertThat(result.note()).contains("来源不可读");
  }

  @Test
  void noopCollectorIsHonest() {
    EventHistoryCollector.CollectionResult result =
        new NoopEventHistoryCollector().collect(Instant.now());
    assertThat(result.executed()).isFalse();
    assertThat(result.note()).contains("未启用");
  }
}
