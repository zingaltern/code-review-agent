package org.cra.llm.internal;

/** 可中断的等待（退避与 RPM 排队共用）：生产实现为 {@code Thread::sleep}，必须传播中断—— 吞掉中断会让等待方在中断后继续持锁空转（09§6）。 */
@FunctionalInterface
public interface Sleeper {

  /**
   * 等待指定毫秒。
   *
   * @param millis 毫秒
   * @throws InterruptedException 等待被中断
   */
  void sleep(long millis) throws InterruptedException;
}
