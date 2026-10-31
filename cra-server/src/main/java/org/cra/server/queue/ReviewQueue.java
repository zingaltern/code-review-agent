package org.cra.server.queue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有界评审队列 + 背压（02§5 服务形态）：容量满时 {@link #submit} 返回 FULL，HTTP 层回 503，平台重投同一事件（GitHub delivery id /
 * GitLab event UUID；幂等键保证不重复评审）。
 *
 * <p>worker 数固定；stop 先停止接收，再等待 worker 把已入队任务执行完（排水超时后中断）——Spring 优雅停机（server.shutdown=graceful）
 * 之后执行，避免把已接受的 webhook 丢掉。
 */
public final class ReviewQueue {

  private static final Logger LOG = LoggerFactory.getLogger(ReviewQueue.class);
  private static final long POLL_MILLIS = 200;
  private static final long TERMINATION_GRACE_SECONDS = 5;

  /** 任务处理器（生产实现 = 更新 delivery 状态 + 调 ReviewExecutor）。 */
  @FunctionalInterface
  public interface JobHandler {
    /**
     * 执行任务；异常由队列计数并记录（不阻断 worker）。
     *
     * @param job 评审任务
     */
    void handle(ReviewJob job);
  }

  /** 入队结果（HTTP 映射：ACCEPTED→202，FULL→503，SHUTDOWN→503）。 */
  public enum SubmitResult {
    /** 已入队。 */
    ACCEPTED,
    /** 队列满（背压）。 */
    FULL,
    /** 正在停机，不再接收。 */
    SHUTDOWN
  }

  /**
   * 队列观测快照（健康检查与日志用）。
   *
   * @param capacity 容量
   * @param queued 当前排队数
   * @param inFlight 正在执行数
   * @param accepted 累计入队
   * @param completed 累计完成
   * @param failed 累计失败
   * @param rejected 累计背压拒绝
   * @param accepting 是否接收新任务
   */
  public record Stats(
      int capacity,
      int queued,
      int inFlight,
      long accepted,
      long completed,
      long failed,
      long rejected,
      boolean accepting) {}

  private final BlockingQueue<ReviewJob> queue;
  private final int capacity;
  private final int workerCount;
  private final long drainTimeoutMillis;
  private final JobHandler handler;
  private final ExecutorService workers;
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  /**
   * @param capacity 队列容量（>0）
   * @param workerCount worker 数（>0）
   * @param drainTimeoutSeconds 停机排水超时秒数
   * @param handler 任务处理器
   */
  public ReviewQueue(int capacity, int workerCount, int drainTimeoutSeconds, JobHandler handler) {
    if (capacity <= 0 || workerCount <= 0 || drainTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("capacity/workerCount/drainTimeout 必须为正");
    }
    if (handler == null) {
      throw new IllegalArgumentException("handler 不能为空");
    }
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.capacity = capacity;
    this.workerCount = workerCount;
    this.drainTimeoutMillis = TimeUnit.SECONDS.toMillis(drainTimeoutSeconds);
    this.handler = handler;
    this.workers =
        Executors.newFixedThreadPool(
            workerCount,
            runnable -> {
              Thread thread = new Thread(runnable, "cra-server-review");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** 启动 worker（Spring initMethod；重复调用安全）。 */
  public void start() {
    if (!accepting.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < workerCount; i++) {
      workers.submit(this::loop);
    }
    LOG.info(
        "review 队列启动: capacity={} workers={} drainTimeoutMs={}",
        capacity,
        workerCount,
        drainTimeoutMillis);
  }

  /**
   * 入队（非阻塞；满即背压）。
   *
   * @param job 评审任务
   * @return 入队结果
   */
  public SubmitResult submit(ReviewJob job) {
    if (!accepting.get()) {
      return SubmitResult.SHUTDOWN;
    }
    if (queue.offer(job)) {
      accepted.incrementAndGet();
      LOG.info(
          "review 已入队: delivery={} repo={} pr={} head={}",
          job.deliveryId(),
          job.repoKey(),
          job.prNumber(),
          job.headSha());
      return SubmitResult.ACCEPTED;
    }
    rejected.incrementAndGet();
    LOG.warn(
        "review 队列已满（背压 503）: delivery={} repo={} pr={}",
        job.deliveryId(),
        job.repoKey(),
        job.prNumber());
    return SubmitResult.FULL;
  }

  /** 优雅停机（Spring destroyMethod）：停止接收 → 等待 worker 排水（超时中断）。 */
  public void stop() {
    if (!accepting.compareAndSet(true, false)) {
      return;
    }
    LOG.info("review 队列停机开始: queued={} inFlight={}", queue.size(), inFlight.get());
    // shutdown 不中断运行中的 loop：worker 在 accepting=false 且队列空时自然退出，awaitTermination 覆盖已取走未计数的在途任务
    workers.shutdown();
    boolean drained;
    try {
      drained = workers.awaitTermination(drainTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      drained = false;
    }
    if (!drained) {
      workers.shutdownNow();
      try {
        workers.awaitTermination(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    LOG.info(
        "review 队列停机完成: drained={} queued={} inFlight={} accepted={} completed={} failed={} rejected={}",
        drained,
        queue.size(),
        inFlight.get(),
        accepted.get(),
        completed.get(),
        failed.get(),
        rejected.get());
  }

  /**
   * 观测快照。
   *
   * @return 队列统计
   */
  public Stats stats() {
    return new Stats(
        capacity,
        queue.size(),
        inFlight.get(),
        accepted.get(),
        completed.get(),
        failed.get(),
        rejected.get(),
        accepting.get());
  }

  private void loop() {
    while (accepting.get() || !queue.isEmpty()) {
      ReviewJob job;
      try {
        job = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (job == null) {
        continue;
      }
      inFlight.incrementAndGet();
      try {
        handler.handle(job);
        completed.incrementAndGet();
      } catch (RuntimeException e) {
        failed.incrementAndGet();
        LOG.error(
            "review 任务失败: delivery={} repo={} pr={}",
            job.deliveryId(),
            job.repoKey(),
            job.prNumber(),
            e);
      } finally {
        inFlight.decrementAndGet();
      }
    }
  }
}
