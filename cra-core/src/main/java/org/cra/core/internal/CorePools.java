package org.cra.core.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * cra-core 的共享固定线程池（09§6【强制】：池在 cra-core 单例创建，组件不得自建线程池）。
 *
 * <p>常驻 server 会按仓库缓存 orchestrator：若每个 pipeline 实例各建池，线程数随仓库数线性增长且永不回收。 两个池都是 daemon
 * 且进程级复用，宽度分别对应评审扇出与验证批次并发。
 */
public final class CorePools {

  /** 评审扇出并发（02§4.5：与 LLM 并发信号量同宽）。 */
  public static final int REVIEW_PARALLEL = 8;

  /** 验证批次并发（02§4.4：≤4 批并行）。 */
  public static final int VERIFY_PARALLEL = 4;

  private static final ExecutorService REVIEW = pool("cra-review", REVIEW_PARALLEL);
  private static final ExecutorService VERIFY = pool("cra-verify", VERIFY_PARALLEL);

  private CorePools() {}

  /**
   * 评审扇出池（进程级单例）。
   *
   * @return 固定宽度 8 的 daemon 线程池
   */
  public static ExecutorService reviewPool() {
    return REVIEW;
  }

  /**
   * 验证批次池（进程级单例）。
   *
   * @return 固定宽度 4 的 daemon 线程池
   */
  public static ExecutorService verifyPool() {
    return VERIFY;
  }

  private static ExecutorService pool(String name, int width) {
    AtomicInteger seq = new AtomicInteger();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, name + "-" + seq.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return Executors.newFixedThreadPool(width, factory);
  }
}
