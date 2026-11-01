package org.cra.server.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 有界队列与背压（02§5）：满即 FULL、停机后 SHUTDOWN、优雅排水。 */
class ReviewQueueTest {

  private static ReviewJob job(String id) {
    return new ReviewJob(id, "o", "r", 1, "base", "head-" + id);
  }

  @Test
  void rejectsWhenFull() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ReviewQueue queue =
        new ReviewQueue(
            1,
            1,
            5,
            j -> {
              started.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    queue.start();
    assertThat(queue.submit(job("d1"))).isEqualTo(ReviewQueue.SubmitResult.ACCEPTED);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(queue.submit(job("d2"))).isEqualTo(ReviewQueue.SubmitResult.ACCEPTED);
    assertThat(queue.submit(job("d3"))).isEqualTo(ReviewQueue.SubmitResult.FULL);
    assertThat(queue.stats().rejected()).isEqualTo(1);
    assertThat(queue.stats().queued()).isEqualTo(1);
    release.countDown();
    queue.stop();
  }

  @Test
  void stopDrainsAcceptedJobs() {
    ReviewQueue queue = new ReviewQueue(10, 2, 5, j -> sleepQuietly(20));
    queue.start();
    queue.submit(job("d1"));
    queue.submit(job("d2"));
    queue.submit(job("d3"));
    queue.stop();
    assertThat(queue.stats().completed()).isEqualTo(3);
    assertThat(queue.stats().accepting()).isFalse();
  }

  @Test
  void submitBeforeStartOrAfterStopIsShutdown() {
    ReviewQueue queue = new ReviewQueue(1, 1, 5, j -> {});
    assertThat(queue.submit(job("d1"))).isEqualTo(ReviewQueue.SubmitResult.SHUTDOWN);
    queue.start();
    queue.stop();
    assertThat(queue.submit(job("d2"))).isEqualTo(ReviewQueue.SubmitResult.SHUTDOWN);
  }

  /**
   * submit/stop 竞态窗的行为契约（D3）：stop 前已 offer 的任务仍被正常执行（排水语义，stop 返回即排水完成， 确定性）；stop 后 submit 如实拒绝——返回
   * SHUTDOWN、不入队不计数，无"假成功 202"滞留。
   */
  @Test
  void stopThenSubmitIsRejectedWithoutResidue() {
    List<String> handled = new ArrayList<>();
    ReviewQueue queue = new ReviewQueue(10, 1, 5, j -> handled.add(j.deliveryId()));
    queue.start();
    assertThat(queue.submit(job("d1"))).isEqualTo(ReviewQueue.SubmitResult.ACCEPTED);
    queue.stop();
    assertThat(handled).containsExactly("d1");
    assertThat(queue.stats().completed()).isEqualTo(1);
    assertThat(queue.submit(job("d2"))).isEqualTo(ReviewQueue.SubmitResult.SHUTDOWN);
    assertThat(queue.stats().queued()).isZero();
    assertThat(queue.stats().accepted()).isEqualTo(1);
    assertThat(queue.stats().completed()).isEqualTo(1);
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
