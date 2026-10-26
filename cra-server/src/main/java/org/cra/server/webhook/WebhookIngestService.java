package org.cra.server.webhook;

import java.time.Instant;
import org.cra.server.delivery.DeliveryRecord;
import org.cra.server.delivery.DeliveryStore;
import org.cra.server.queue.ReviewJob;
import org.cra.server.queue.ReviewQueue;
import org.springframework.stereotype.Service;

/**
 * webhook 入站编排：幂等登记 → 入队（背压即释放占位，让 GitHub 重投可再次 claim）→ 202。
 *
 * <p>顺序不可交换：先 claim 再入队，保证同 delivery 并发两次只有一次入队；队列满时释放占位，HTTP 回 503，GitHub 重投同一 delivery 时重新
 * claim（02§5）。
 */
@Service
public final class WebhookIngestService {

  /** 入站结果（HTTP 映射）。 */
  public enum Outcome {
    /** 已入队（202）。 */
    ACCEPTED,
    /** 重复 delivery（200）。 */
    DUPLICATE,
    /** 队列满背压（503 + Retry-After）。 */
    BACKPRESSURE
  }

  private final DeliveryStore deliveryStore;
  private final ReviewQueue reviewQueue;

  /**
   * @param deliveryStore delivery 幂等登记
   * @param reviewQueue 有界队列
   */
  public WebhookIngestService(DeliveryStore deliveryStore, ReviewQueue reviewQueue) {
    this.deliveryStore = deliveryStore;
    this.reviewQueue = reviewQueue;
  }

  /**
   * 处理已通过验签与事件过滤的 webhook。
   *
   * @param event PR 事件
   * @return 入站结果
   */
  public Outcome ingest(PullRequestEvent event) {
    Instant now = Instant.now();
    DeliveryRecord record =
        new DeliveryRecord(
            event.deliveryId(),
            event.repoKey(),
            event.prNumber(),
            event.headSha(),
            event.action(),
            DeliveryRecord.RECEIVED,
            null,
            null,
            now,
            now);
    if (!deliveryStore.claim(record)) {
      // 成功 delivery 的重投 = duplicate；失败 delivery 的重投允许重试（LLM 不可得等可恢复故障）
      if (!deliveryStore.resetFailed(event.deliveryId())) {
        return Outcome.DUPLICATE;
      }
    }
    ReviewJob job =
        new ReviewJob(
            event.deliveryId(),
            event.repoOwner(),
            event.repoName(),
            event.prNumber(),
            event.baseSha(),
            event.headSha());
    ReviewQueue.SubmitResult submit;
    try {
      submit = reviewQueue.submit(job);
    } catch (RuntimeException e) {
      deliveryStore.release(event.deliveryId());
      throw e;
    }
    if (submit == ReviewQueue.SubmitResult.ACCEPTED) {
      return Outcome.ACCEPTED;
    }
    // 未入队（满/停机）→ 释放占位；GitHub 依据 503 重投，重投时必须能再次 claim
    deliveryStore.release(event.deliveryId());
    return Outcome.BACKPRESSURE;
  }
}
