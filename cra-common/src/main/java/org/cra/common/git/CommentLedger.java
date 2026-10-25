package org.cra.common.git;

import java.util.List;

/**
 * 账本拉取结果（GitPlatformAdapter.fetchBotComments / CommentSource.fetchBotComments 返回值，
 * 05§1.3/ADR-0005）：bot 自身评论 + 解析出的两类标记 + 可疑标记计数。
 *
 * @param comments bot 评论原文列表（inline 与 issue 已按端点合并，05§2.3）
 * @param findingMarkers 解析成功的 finding 级标记
 * @param runMarkers 解析成功的 run 完成标记
 * @param suspectCount 可疑标记数（格式非法/位置非法等被忽略的 cra: 标记；同一 PR ≥3 时 Summary 警告疑似注入尝试，05§1.3 规则 2）
 */
public record CommentLedger(
    List<LedgerComment> comments,
    List<LedgerFindingMarker> findingMarkers,
    List<LedgerRunMarker> runMarkers,
    int suspectCount) {

  public CommentLedger {
    comments = List.copyOf(comments == null ? List.of() : comments);
    findingMarkers = List.copyOf(findingMarkers == null ? List.of() : findingMarkers);
    runMarkers = List.copyOf(runMarkers == null ? List.of() : runMarkers);
    if (suspectCount < 0) {
      throw new IllegalArgumentException("suspectCount must be non-negative");
    }
  }

  /**
   * 账本解析兜底分层判据（05§1.3 规则 5）：是否存在任何有效标记。
   *
   * @return true 若至少解析出一个有效标记
   */
  public boolean hasAnyValidMarker() {
    return !findingMarkers.isEmpty() || !runMarkers.isEmpty();
  }
}
