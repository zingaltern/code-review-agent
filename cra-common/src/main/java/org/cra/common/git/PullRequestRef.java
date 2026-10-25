package org.cra.common.git;

import java.time.Instant;
import java.util.Objects;

/**
 * PR 窗口条目（06§1.1 周报取数目录：{@link CommentSource#listPullRequests} 返回值，05§2.1）。
 *
 * <p>账本与处置采集均按 PR 组织，周报需要先知道窗口内有哪些 PR；平台不提供列表时返回空列表并由消费方声明"不采" （11§4 第 5 条，禁止抛异常假装支持）。
 *
 * @param number PR 编号
 * @param headSha PR 分支 head（评论锚定 commit 的后续变更比较基点）
 * @param mergeCommitSha 合并提交 sha（未合并为 null）
 * @param state 平台状态：open|closed（是否合并看 {@link #mergedAt()}）
 * @param updatedAt 最近更新时间（窗口过滤依据）
 * @param mergedAt 合并时间（未合并为 null）
 */
public record PullRequestRef(
    int number,
    String headSha,
    String mergeCommitSha,
    String state,
    Instant updatedAt,
    Instant mergedAt) {

  /** 平台状态 open。 */
  public static final String STATE_OPEN = "open";

  /** 平台状态 closed（合并与否由 mergedAt 判定）。 */
  public static final String STATE_CLOSED = "closed";

  public PullRequestRef {
    if (number <= 0) {
      throw new IllegalArgumentException("number must be positive: " + number);
    }
    Objects.requireNonNull(headSha, "headSha");
    if (headSha.isBlank()) {
      throw new IllegalArgumentException("headSha must be non-blank");
    }
    state = state == null || state.isBlank() ? STATE_CLOSED : state;
    if (!STATE_OPEN.equals(state) && !STATE_CLOSED.equals(state)) {
      throw new IllegalArgumentException("state must be open|closed: " + state);
    }
    Objects.requireNonNull(updatedAt, "updatedAt");
    mergeCommitSha = mergeCommitSha == null || mergeCommitSha.isBlank() ? null : mergeCommitSha;
  }

  /**
   * 是否已合并（合并时间非空即合并；GitHub 的 squash/rebase 合并同样有 merged_at）。
   *
   * @return true 若已合并
   */
  public boolean merged() {
    return mergedAt != null;
  }
}
