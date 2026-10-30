package org.cra.core.internal;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享池任务句柄：记录任务实际开始时间，供外层有界等待区分 <b>排队超时</b>（尚未开始，startedAt=0）与 <b>执行超时</b>（已开始）。
 *
 * <p>09§6 共享静态池意味着 future.get(timeout) 的计时包含排队时间；同一超时值不能同时表达"池被其它 run 占满"与"单个代理卡住"， 两者需要不同的降级文案与处置。
 *
 * @param <T> 任务结果类型
 */
public final class TaskHandle<T> {

  private final Future<T> future;
  private final AtomicLong startedAtMillis;

  private TaskHandle(Future<T> future, AtomicLong startedAtMillis) {
    this.future = future;
    this.startedAtMillis = startedAtMillis;
  }

  /**
   * 提交任务到指定池（任务体第一行记录开始时间）。
   *
   * @param pool 执行池（进程级共享池的调用方仍持池）
   * @param task 任务体
   * @param <T> 结果类型
   * @return 句柄
   */
  public static <T> TaskHandle<T> submit(ExecutorService pool, Callable<T> task) {
    AtomicLong startedAt = new AtomicLong();
    Future<T> future =
        pool.submit(
            () -> {
              startedAt.compareAndSet(0L, System.currentTimeMillis());
              return task.call();
            });
    return new TaskHandle<>(future, startedAt);
  }

  /**
   * @return 底层 future
   */
  public Future<T> future() {
    return future;
  }

  /**
   * @return true 若任务体已开始执行（排队中被取消/超时为 false）
   */
  public boolean started() {
    return startedAtMillis.get() > 0L;
  }

  /**
   * @return 实际开始时间（毫秒；未开始为 0）
   */
  public long startedAtMillis() {
    return startedAtMillis.get();
  }

  /**
   * 取消任务（中断执行中任务；排队任务不会开始）。
   *
   * @return future.cancel 结果
   */
  public boolean cancel() {
    return future.cancel(true);
  }
}
