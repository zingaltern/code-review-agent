package org.cra.store.api;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.ReviewKey;

/**
 * 评审账本 SPI（02§5、05§1.5；11§2 稳定级）。读侧：commit 级幂等闸（只认 DONE/DEGRADED 完成标记，02§4.1）与 指纹级去重（已发布 finding
 * 指纹）。写侧（M1，T1.1/T1.2）：完成标记与已发布 finding 的账本化登记 + 过期收尾 （02§5）所需的历史轮查询。三形态：GitHub 评论账本（CI）/
 * SQLite（本地）/ PG（服务，M4）。
 *
 * <p>SPI 演进只加 default 方法（11§4 第 1 条）。写侧 default 语义 = <b>无操作</b>：只读形态（GitHub 评论账本）
 * 的写入由发布动作内嵌标记完成（05§1.3 规则 3），无需重复登记；可持久化形态（SQLite/PG）必须覆写落库， 否则幂等闸与补发将失去事实源。
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

  /**
   * 该 PR 上全部 run 完成标记（过期收尾数据面，02§5：新 run 发布成功后对旧 head_sha 的 Summary issue comment 加取代前缀；目标 = 本列表中
   * commit 不同于当前 head 的条目）。
   *
   * @param prNumber PR 编号
   * @return 完成标记列表（按发布时间序）；default 无操作返回空——只读形态无历史轮登记时不做收尾
   */
  default List<LedgerRunMarker> completedRuns(int prNumber) {
    return List.of();
  }

  /**
   * 可疑账本标记计数（05§1.3 规则 2 的 LEDGER_SUSPECT；同一 PR ≥3 时 Summary 告警疑似注入尝试）。
   *
   * @param prNumber PR 编号
   * @return 可疑标记数；default 0（无可信计数来源的形态不告警）
   */
  default int suspectMarkerCount(int prNumber) {
    return 0;
  }

  /**
   * 取单条账本评论原文（过期收尾 PATCH 需要 body 做幂等前缀；02§5）。
   *
   * @param prNumber PR 编号
   * @param commentId 评论 id
   * @return 评论原文；不存在或形态不持久化原文（如 SQLite）为 empty
   */
  default Optional<LedgerComment> comment(int prNumber, long commentId) {
    return Optional.empty();
  }

  /**
   * 登记 run 完成标记（可持久化形态覆写落库；GitHub 评论账本保持 default——标记已随 Summary issue comment 发布，账本即评论本身，重登记无意义）。
   *
   * @param prNumber PR 编号
   * @param marker 完成标记（status ∈ DONE|DEGRADED，commentId = Summary issue comment id）
   */
  default void recordRunCompletion(int prNumber, LedgerRunMarker marker) {}

  /**
   * 登记已发布 finding 标记（可持久化形态覆写落库；GitHub 评论账本保持 default——标记已内嵌于 inline 评论正文，05§1.3 规则 3）。位置非法降级进
   * Summary 的 finding 也须登记（commentId = 0），否则半截 run 重跑会重发（02§4.1 补发语义）。
   *
   * @param prNumber PR 编号
   * @param marker finding 标记
   */
  default void recordPublishedFinding(int prNumber, LedgerFindingMarker marker) {}

  /**
   * 登记本次 run 实际使用的 prompt 模板版本（角色 → 版本，形如 {@code general-reviewer@1a2b3c04}； 05§1.2
   * prompt_template_versions 与 run 关联、 05§1.5 prompt_template_version 表；T2.11）。CI 形态版本随 trace
   * artifact 留痕；可持久化形态（SQLite/PG）覆写落库，GitHub 评论账本保持 default 无操作（评论账本不承载该数据面）。
   *
   * @param prNumber PR 编号（本地形态为 0）
   * @param runId run 标识
   * @param versions 角色 → 模板版本
   */
  default void recordTemplateVersions(
      int prNumber, String runId, java.util.Map<String, String> versions) {}

  // ---------- 键控扩展（M4c/M4d，05§1.3；SPI 只加 default 方法，11§4 第 1 条） ----------

  /**
   * 按账本键查询完成标记（PR 形态 = (repo, pr, commit)；push/夜间形态 = (repo, commit)）。
   *
   * <p>默认委托既有 PR 形态实现——只读评论账本与 SQLite 形态在 PR 模式下语义不变；无 PR 载体账本覆写本方法， 按载体端点作用域或标记内 commit 过滤。
   *
   * @param key 账本键
   * @return 完成标记；不存在则 empty
   */
  default Optional<LedgerRunMarker> findCompletion(ReviewKey key) {
    return findCompletion(key.prNumber(), key.commitSha());
  }

  /**
   * 按账本键查询已发布 finding 指纹。
   *
   * @param key 账本键
   * @return 标记列表（无 PR 载体为单载体评论，通常为空——载体为原子发布，不存在半截补发）
   */
  default List<LedgerFindingMarker> publishedFindings(ReviewKey key) {
    return publishedFindings(key.prNumber());
  }

  /**
   * 按账本键查询历史完成标记（过期收尾与收敛轮次数据面）。
   *
   * @param key 账本键
   * @return 完成标记列表
   */
  default List<LedgerRunMarker> completedRuns(ReviewKey key) {
    return completedRuns(key.prNumber());
  }

  /**
   * 按账本键统计可疑标记（05§1.3 规则 2）。
   *
   * @param key 账本键
   * @return 可疑标记数
   */
  default int suspectMarkerCount(ReviewKey key) {
    return suspectMarkerCount(key.prNumber());
  }

  /**
   * 按账本键取单条载体评论原文（无 PR 形态的载体是 commit comment / issue，id 为对应载体 id）。
   *
   * @param key 账本键
   * @param commentId 载体 id
   * @return 原文；不存在或形态不持久化原文为 empty
   */
  default Optional<LedgerComment> comment(ReviewKey key, long commentId) {
    return comment(key.prNumber(), commentId);
  }

  /**
   * 按账本键登记完成标记（可持久化形态覆写；GitHub 载体账本保持无操作——标记已内嵌载体正文）。
   *
   * @param key 账本键
   * @param marker 完成标记
   */
  default void recordRunCompletion(ReviewKey key, LedgerRunMarker marker) {
    recordRunCompletion(key.prNumber(), marker);
  }

  /**
   * 按账本键登记已发布 finding（可持久化形态覆写；GitHub 载体账本保持无操作）。
   *
   * @param key 账本键
   * @param marker finding 标记
   */
  default void recordPublishedFinding(ReviewKey key, LedgerFindingMarker marker) {
    recordPublishedFinding(key.prNumber(), marker);
  }

  /**
   * 按账本键登记模板版本。
   *
   * @param key 账本键
   * @param runId run 标识
   * @param versions 角色 → 模板版本
   */
  default void recordTemplateVersions(
      ReviewKey key, String runId, java.util.Map<String, String> versions) {
    recordTemplateVersions(key.prNumber(), runId, versions);
  }
}
