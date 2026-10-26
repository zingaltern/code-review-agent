package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cra.server.delivery.DeliveryRecord;
import org.cra.server.queue.ReviewQueue;
import org.cra.server.support.InMemoryDeliveryStore;
import org.junit.jupiter.api.Test;

/** webhook 入站幂等与背压（02§5）：同 delivery 两次只入队一次；满队列 503 且释放占位。 */
class WebhookIngestServiceTest {

  private static PullRequestEvent event(String delivery) {
    return new PullRequestEvent(delivery, "opened", "o", "r", 7, "base", "head-" + delivery);
  }

  @Test
  void sameDeliveryIsAcceptedOnce() {
    InMemoryDeliveryStore store = new InMemoryDeliveryStore();
    ReviewQueue queue = new ReviewQueue(10, 1, 5, job -> {});
    queue.start();
    WebhookIngestService service = new WebhookIngestService(store, queue);

    assertThat(service.ingest(event("d1"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    assertThat(service.ingest(event("d1"))).isEqualTo(WebhookIngestService.Outcome.DUPLICATE);
    assertThat(queue.stats().accepted()).isEqualTo(1);
    assertThat(store.find("d1")).isPresent();
    queue.stop();
  }

  @Test
  void failedDeliveryCanBeRetriedByRedelivery() {
    InMemoryDeliveryStore store = new InMemoryDeliveryStore();
    ReviewQueue queue = new ReviewQueue(10, 1, 5, job -> {});
    queue.start();
    WebhookIngestService service = new WebhookIngestService(store, queue);

    assertThat(service.ingest(event("d1"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    await(() -> queue.stats().completed() == 1);
    store.markFailed("d1", "transient llm failure");
    assertThat(service.ingest(event("d1"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    assertThat(store.find("d1")).map(DeliveryRecord::status).contains(DeliveryRecord.RECEIVED);
    queue.stop();
  }

  @Test
  void backpressureReleasesClaimSoRedeliveryCanSucceed() throws Exception {
    InMemoryDeliveryStore store = new InMemoryDeliveryStore();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ReviewQueue queue =
        new ReviewQueue(
            1,
            1,
            5,
            job -> {
              started.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    queue.start();
    WebhookIngestService service = new WebhookIngestService(store, queue);

    assertThat(service.ingest(event("d1"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(service.ingest(event("d2"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    assertThat(service.ingest(event("d3"))).isEqualTo(WebhookIngestService.Outcome.BACKPRESSURE);
    assertThat(store.find("d3")).isEmpty();

    // GitHub 重投同 delivery：占位已释放，可再次接收
    release.countDown();
    await(() -> queue.stats().queued() == 0);
    assertThat(service.ingest(event("d3"))).isEqualTo(WebhookIngestService.Outcome.ACCEPTED);
    assertThat(store.find("d3")).map(DeliveryRecord::status).contains(DeliveryRecord.RECEIVED);
    queue.stop();
  }

  private static void await(java.util.function.BooleanSupplier condition) {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断", e);
      }
    }
    throw new AssertionError("等待条件超时");
  }
}
