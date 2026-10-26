package org.cra.server.pipeline;

import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.server.delivery.DeliveryStore;
import org.cra.server.queue.ReviewJob;
import org.cra.server.queue.ReviewQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 队列任务处理器：delivery 状态机（RECEIVED → PROCESSING → COMPLETED/FAILED）+ 调 {@link ReviewExecutor}。
 *
 * <p>重复 delivery 在入站层已被拒（不会到达这里）；同一 head 的重跑由 core 的 commit 闸返回 SKIPPED，delivery 记为 COMPLETED。
 */
public final class ReviewJobHandler implements ReviewQueue.JobHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ReviewJobHandler.class);

  private final DeliveryStore deliveryStore;
  private final ReviewExecutor executor;

  /**
   * @param deliveryStore delivery 状态登记
   * @param executor 评审执行器
   */
  public ReviewJobHandler(DeliveryStore deliveryStore, ReviewExecutor executor) {
    this.deliveryStore = deliveryStore;
    this.executor = executor;
  }

  @Override
  public void handle(ReviewJob job) {
    deliveryStore.markProcessing(job.deliveryId());
    try {
      ReviewResult result = executor.execute(job);
      if (result.status() == RunStatus.FAILED) {
        deliveryStore.markFailed(job.deliveryId(), result.runId() + " " + result.summaryLine());
        LOG.warn(
            "review FAILED: delivery={} repo={} pr={} run={} summary={}",
            job.deliveryId(),
            job.repoKey(),
            job.prNumber(),
            result.runId(),
            result.summaryLine());
        return;
      }
      deliveryStore.markCompleted(
          job.deliveryId(),
          result.runId(),
          result.status() + " findings=" + result.stats().findingsPublished());
    } catch (RuntimeException e) {
      deliveryStore.markFailed(job.deliveryId(), "exception: " + e);
      throw e;
    }
  }
}
