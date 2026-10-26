package org.cra.server.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** M4f-full 调度骨架（07§5 M4b）：关闭时不执行；开启时调采集端口并如实报告"未实现"。 */
class EventHistoryCollectionJobTest {

  @Test
  void disabledJobDoesNotCollect() {
    boolean[] called = {false};
    EventHistoryCollector collector =
        since -> {
          called[0] = true;
          return new EventHistoryCollector.CollectionResult(true, 1, 1, "x");
        };
    EventHistoryCollectionJob job = new EventHistoryCollectionJob(false, collector);
    EventHistoryCollector.CollectionResult result = job.runOnce();
    assertThat(result.executed()).isFalse();
    assertThat(result.note()).isEqualTo("disabled");
    assertThat(called[0]).isFalse();
  }

  @Test
  void enabledJobInvokesCollector() {
    EventHistoryCollector collector =
        since -> new EventHistoryCollector.CollectionResult(true, 2, 3, "collected");
    EventHistoryCollectionJob job = new EventHistoryCollectionJob(true, collector);
    EventHistoryCollector.CollectionResult result = job.runOnce();
    assertThat(result.executed()).isTrue();
    assertThat(result.tickets()).isEqualTo(2);
    assertThat(result.incidents()).isEqualTo(3);
  }

  @Test
  void noopCollectorIsHonest() {
    EventHistoryCollector.CollectionResult result =
        new NoopEventHistoryCollector().collect(Instant.now());
    assertThat(result.executed()).isFalse();
    assertThat(result.note()).contains("M4f-full");
  }
}
