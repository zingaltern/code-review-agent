package org.cra.common.signoff;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.LedgerRunMarker;

/**
 * 签字建议的 commit 绑定与 stale 判定（07§5 M4a；05§1.3 账本语义同源）。
 *
 * <p>规则：approve 只对特定 commit 有效。签字建议绑定生成时的 head sha；账本中出现**更新的**完成标记 （不同 commit 且发布序在后）后， 旧建议即
 * stale，必须重新核实——与 commit 级幂等闸（DONE 只认同一 commit） 和指纹去重共享同一事实源。
 */
public final class SignoffBinding {

  private SignoffBinding() {}

  /**
   * 建议对指定 commit 是否仍然有效。
   *
   * @param advice 签字建议
   * @param currentCommitSha 当前评审/审批目标 commit
   * @return true 若建议非 stale 且绑定的 commit 与目标一致
   */
  public static boolean validFor(SignoffAdvice advice, String currentCommitSha) {
    return advice != null
        && advice.advisoryOnly()
        && !advice.stale()
        && currentCommitSha != null
        && advice.commitSha().equalsIgnoreCase(currentCommitSha);
  }

  /**
   * 账本比对：找出使建议 stale 的新 commit。
   *
   * <p>{@code completedRuns} 按发布时间序（{@link LedgerRunMarker} 契约）。若建议绑定的 commit 在列表中最后一次出现之后 仍有不同
   * commit 的完成标记，则这些更新轮次使建议 stale；返回其中最后一次的 commit。建议 commit 不在账本 中（例如本地建议尚未发布）时，任何不同 commit
   * 的完成标记都视为更新。
   *
   * @param adviceCommitSha 建议绑定的 commit
   * @param completedRuns 该 PR 全部完成标记（按发布时间序）
   * @return 使建议 stale 的最新 commit；无则 empty
   */
  public static Optional<String> supersedingCommit(
      String adviceCommitSha, List<LedgerRunMarker> completedRuns) {
    if (adviceCommitSha == null
        || adviceCommitSha.isBlank()
        || completedRuns == null
        || completedRuns.isEmpty()) {
      return Optional.empty();
    }
    int lastSame = -1;
    int lastDifferent = -1;
    String lastDifferentSha = null;
    for (int i = 0; i < completedRuns.size(); i++) {
      String sha = completedRuns.get(i).commitSha();
      if (sha != null && sha.equalsIgnoreCase(adviceCommitSha)) {
        lastSame = i;
      } else {
        lastDifferent = i;
        lastDifferentSha = sha;
      }
    }
    return lastDifferent > lastSame ? Optional.ofNullable(lastDifferentSha) : Optional.empty();
  }

  /**
   * 将建议标记为 stale（不可变复制）。
   *
   * @param advice 原建议
   * @param newCommitSha 取代它的新 commit
   * @return stale 标记后的建议
   */
  public static SignoffAdvice markStale(SignoffAdvice advice, String newCommitSha) {
    if (newCommitSha == null || newCommitSha.isBlank()) {
      throw new IllegalArgumentException("newCommitSha must be non-blank");
    }
    if (advice.stale()) {
      return advice;
    }
    return new SignoffAdvice(
        advice.applicable(),
        advice.decision(),
        advice.tier(),
        advice.recommendedSigners(),
        advice.commitSha(),
        advice.context(),
        advice.rules(),
        true,
        "新 commit "
            + newCommitSha
            + " 已出现；原件签字建议（绑定 commit "
            + advice.commitSha()
            + "）已 stale，须重新核实");
  }
}
