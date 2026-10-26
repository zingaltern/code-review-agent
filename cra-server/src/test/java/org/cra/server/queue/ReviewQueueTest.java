package org.cra.server.queue;

import static org.assertj.core.api.Assertions.assertThat;

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

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
