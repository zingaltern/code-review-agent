package org.cra.server.history;

import java.time.Instant;

/**
 * 产品事件史采集端口（M4f-full 的数据面；07§5 M4b 只落接口与调度骨架）。
 *
 * <p>M4f-full 实现将从工单/事故复盘等来源结构化入库；tribal knowledge 不可见是硬边界（01§3.4），本接口只覆盖已文档化部分。
 */
public interface EventHistoryCollector {

  /**
   * 采集一个窗口的事件史。
   *
   * @param since 窗口起点
   * @return 采集结果
   */
  CollectionResult collect(Instant since);

  /**
   * 采集结果。
   *
   * @param executed 是否实际执行（false = 骨架/未启用）
   * @param tickets 入库工单数
   * @param incidents 入库事故复盘数
   * @param note 说明
   */
  record CollectionResult(boolean executed, int tickets, int incidents, String note) {

    /**
     * 未启用的空结果。
     *
     * @param reason 原因
     * @return 结果
     */
    public static CollectionResult skipped(String reason) {
      return new CollectionResult(false, 0, 0, reason);
    }
  }
}
