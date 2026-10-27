package org.cra.common.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 事件史存储端口（ADR-0012；采集器写入 + CrossChecker 读取）。
 *
 * <p>幂等键 = (repo, event_id)：同键重复写入按覆盖语义更新内容，绝不产生第二行。水位（checkpoint）按事件源标识
 * 保存，是增量窗口的持久化依据；采集器只在成功入库且无解析失败时推进水位。
 */
public interface EventHistoryStore extends EventHistoryQuery {

  /**
   * 批量幂等入库（同键覆盖；批内同键以后出现者为准）。
   *
   * @param events 待入库事件（非 null；可为空）
   * @return 计数（新插入 / 覆盖已有）
   */
  UpsertResult upsertAll(List<ProductEvent> events);

  /**
   * 读取事件源水位。
   *
   * @param sourceId 事件源标识（如 JSONL 位置）
   * @return 已成功采集到的最大发生时间；从未成功为空
   */
  Optional<Instant> checkpoint(String sourceId);

  /**
   * 保存事件源水位（只前进不后退）。
   *
   * @param sourceId 事件源标识
   * @param watermark 水位（成功入库事件的最大发生时间）
   */
  void saveCheckpoint(String sourceId, Instant watermark);

  /**
   * 入库计数。
   *
   * @param inserted 新插入行数
   * @param updated 覆盖已有行数（内容相同也计入覆盖）
   */
  record UpsertResult(int inserted, int updated) {

    /**
     * 总行数。
     *
     * @return inserted + updated
     */
    public int total() {
      return inserted + updated;
    }
  }
}
