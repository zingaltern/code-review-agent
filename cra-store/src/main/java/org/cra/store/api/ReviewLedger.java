package org.cra.store.api;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;

/**
 * 评审账本 SPI（02§5、05§1.5；11§2 稳定级）。M0 为读侧：commit 级幂等闸（只认 DONE/DEGRADED 完成标记，02§4.1）与指纹级去重（已发布 finding
 * 指纹）。写侧随 M1 本地 SQLite 形态以 default 方法扩展（11§4 第 1 条：只加 default 方法）。三形态：GitHub 评论账本（CI）/ SQLite（本地）/
 * PG（服务，M4）。
 */
public interface ReviewLedger {

  /**
   * 查询某 PR 上某 commit 是否已有 run 完成标记（DONE 或 DEGRADED 均算完整发布，05§1.3）。 PR 维度入参：GitHub 评论账本天然按 PR
   * 组织（02§5），commit 闸在同一 PR 内查询。
   *
   * @param prNumber PR 编号
   * @param commitSha head sha
   * @return 完成标记；不存在则 empty
   */
  Optional<LedgerRunMarker> findCompletion(int prNumber, String commitSha);

  /**
   * 已发布 finding 指纹集合（指纹级去重依据，02§4.1 第二道闸）。
   *
   * @param prNumber PR 编号
   * @return 该 PR 上 bot 已发布的全部 finding 标记
   */
  List<LedgerFindingMarker> publishedFindings(int prNumber);
}
