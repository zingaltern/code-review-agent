package org.cra.server.history;

import java.time.Instant;

/**
 * 产品事件史采集端口（M4f-full 的数据面；11§2 登记的演进扩展点）。
 *
 * <p>第一版实现经 JSONL 来源（{@link JsonlEventHistorySource} → {@link IncrementalEventHistoryCollector} →
 * {@link PostgresEventHistoryStore} 落库）；Jira / Confluence / 服务台等 API 连接器是后续扩展，实现本接口即可接入调度与入库。tribal
 * knowledge 不可见是硬边界（01§3.4），本接口只覆盖已文档化部分。
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
   * 采集结果（M4f-full：计数让"采集了什么/失败在哪"可审计，不伪装成功）。
   *
   * @param executed 是否实际执行并完成（false = 未启用/来源读取失败/入库失败）
   * @param tickets 新入库的工单事件数（type=ticket）
   * @param incidents 新入库的事故复盘事件数（type=incident|postmortem）
   * @param note 说明（窗口、计数、重试、失败原因、水位是否推进）
   * @param fetched 来源读取后落在窗口内的候选事件数
   * @param inserted 新插入行数
   * @param updated 覆盖已有行数（同键重复采集）
   * @param skipped 本次未入库数（窗口外/批内重复/单次上限截断）
   * @param failed 解析失败行数（>0 时不推进水位）
   */
  record CollectionResult(
      boolean executed,
      int tickets,
      int incidents,
      String note,
      int fetched,
      int inserted,
      int updated,
      int skipped,
      int failed) {

    /**
     * 便捷构造（测试用最小结果形态；采集器实现返回完整计数与 note）。
     *
     * @param executed 是否执行
     * @param tickets 工单数
     * @param incidents 事故数
     * @param note 说明
     */
    public CollectionResult(boolean executed, int tickets, int incidents, String note) {
      this(executed, tickets, incidents, note, tickets + incidents, tickets + incidents, 0, 0, 0);
    }

    /**
     * 未启用的空结果。
     *
     * @param reason 原因
     * @return 结果
     */
    public static CollectionResult skipped(String reason) {
      return new CollectionResult(false, 0, 0, reason, 0, 0, 0, 0, 0);
    }

    /**
     * 采集失败（来源读取或入库重试耗尽）；计数如实置零并声明可能的部分写入。
     *
     * @param reason 失败原因
     * @return 结果
     */
    public static CollectionResult failed(String reason) {
      return new CollectionResult(false, 0, 0, reason, 0, 0, 0, 0, 0);
    }
  }
}
