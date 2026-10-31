package org.cra.server.delivery;

import java.util.Optional;

/** delivery 幂等登记端口（PG 实现；测试可用内存实现）。 */
public interface DeliveryStore {

  /**
   * 抢占 delivery（INSERT ... ON CONFLICT DO NOTHING）。
   *
   * @param record 待登记记录
   * @return true = 首次接收；false = 重复 delivery
   */
  boolean claim(DeliveryRecord record);

  /**
   * 查询 delivery。
   *
   * @param deliveryId delivery id
   * @return 记录
   */
  Optional<DeliveryRecord> find(String deliveryId);

  /**
   * 标记执行中。
   *
   * @param deliveryId delivery id
   */
  void markProcessing(String deliveryId);

  /**
   * 标记完成。
   *
   * @param deliveryId delivery id
   * @param runId run 标识
   * @param detail 结果摘要（如 SUCCESS/DEGRADED/SKIPPED）
   */
  void markCompleted(String deliveryId, String runId, String detail);

  /**
   * 标记失败。
   *
   * @param deliveryId delivery id
   * @param detail 失败原因（截断后落库）
   */
  void markFailed(String deliveryId, String detail);

  /**
   * 释放接收占位（队列满背压时调用，使平台重投可再次 claim）。
   *
   * @param deliveryId delivery id
   */
  void release(String deliveryId);

  /**
   * 把失败 delivery 重置为 RECEIVED（仅当当前状态为 FAILED；原子操作，多个重投只有一次成功）。
   *
   * <p>语义：成功 delivery 的重投是 duplicate；失败 delivery 的重投允许重试（LLM 不可得等可恢复故障）。
   *
   * @param deliveryId delivery id
   * @return true = 本次重置成功，调用方可重新入队
   */
  boolean resetFailed(String deliveryId);
}
