package org.cra.core.internal.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.git.LedgerRunMarker;

/**
 * 收敛控制（07 T2.7；03§5 M2 形态）：轮次上限 3 + diff-only 复审 + 指纹去重的编排侧载体。
 *
 * <ul>
 *   <li><b>轮次</b>：同一 PR 的验证-复审循环按账本完成标记计数（每个完成的 run = 一轮）；轮次 &gt; 3 = 不收敛 breach——机器互相挑刺天然不收敛（scope
 *       expansion），三轮仍不收敛是设计问题：停止一切新 finding，Summary 明确建议作者打回重新设计而非继续改（03§5 v1.3 语义强化，01§3.4 反模式
 *       3）；
 *   <li><b>diff-only 复审</b>：非首轮只评审自上一轮完成 head 以来的增量 diff（已发布 finding 不重验证， 涉及文件被后续 commit
 *       修改者天然落在增量内）；指纹去重从账本读取（02§5，由 ReviewPipeline 调 {@link
 *       org.cra.core.internal.FingerprintDeduper} 执行）。
 * </ul>
 *
 * <p>纯函数：账本快照走参数（08§5 直接单测）。
 */
public final class ConvergenceGuard {

  /** 验证-复审循环轮次上限（03§5：最多 3 轮）。 */
  static final int MAX_ROUNDS = 3;

  /** 轮次决议。 */
  public record RoundPlan(int round, Optional<String> lastReviewedHead, boolean breached) {}

  private ConvergenceGuard() {}

  /**
   * 依据账本完成标记决议当前轮次。
   *
   * @param completedRuns 该 PR 全部完成标记（按发布时间序，02§5）
   * @param headSha 本次 run 的 head
   * @return 轮次计划：轮号（首次评审 = 1）、上一轮完成 head（diff-only 复审的增量基点）、是否 breach
   */
  public static RoundPlan resolve(List<LedgerRunMarker> completedRuns, String headSha) {
    List<LedgerRunMarker> priorRounds =
        completedRuns.stream().filter(m -> !m.commitSha().equalsIgnoreCase(headSha)).toList();
    int round = priorRounds.size() + 1;
    Optional<String> lastHead =
        priorRounds.isEmpty()
            ? Optional.empty()
            : Optional.of(priorRounds.get(priorRounds.size() - 1).commitSha());
    return new RoundPlan(round, lastHead, round > MAX_ROUNDS);
  }

  /** 不收敛 breach 时的 Summary 建议文案（03§5：打回重新设计而非继续改）。 */
  public static String breachAdvice() {
    return "本 PR 已完成 "
        + MAX_ROUNDS
        + " 轮评审-修复循环仍未收敛（每轮仍有新发现）——"
        + "这通常意味着设计层面的问题，建议打回重新设计，而不是继续在本 PR 上修补";
  }

  /** important/security 级判定（03§4.3 抽查保险丝的作用域）。 */
  public static boolean securityOrImportant(CandidateFinding finding) {
    return finding.category() == FindingCategory.SECURITY
        || finding.severityProposed() == Severity.IMPORTANT;
  }

  /** finding 类别小写（账本标记与 trace 口径）。 */
  public static String categoryLower(CandidateFinding finding) {
    return finding.category().name().toLowerCase(Locale.ROOT);
  }
}
