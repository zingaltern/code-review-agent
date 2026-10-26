package org.cra.common.git;

import java.util.Objects;
import org.cra.common.run.ReviewMode;

/**
 * 评审账本键（M4c/M4d 键控扩展，05§1.3）。
 *
 * <p>PR 形态以 {@code (repo, pr, commit)} 键控（02§5：GitHub 评论账本天然按 PR 组织）；无 PR 形态以 {@code (repo,
 * commit)} 键控——push 直推的载体是 commit comment（端点本身按 commit 作用域），夜间存量的载体是 issue（按标记内 commit
 * 过滤）。两种键共同表达"这个 head commit 是否已完整发布"。
 *
 * <p>标记格式（05§1.3）**不随键控扩展改变**：run 完成标记仍为 {@code v | run | commit | status | findings |
 * suppressed}，一份载体评论只承载一个末位标记——因此无 PR 形态的账本粒度是**载体评论级**（单条 commit comment / 单条 issue），不存在 PR 形态的多条
 * inline 评论拼合。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param mode 发布形态（PR / commit / issue）
 * @param prNumber PR 编号（COMMIT 形态为 0；PULL_REQUEST / ISSUE 形态为正数）
 * @param commitSha 被评审的 head sha
 */
public record ReviewKey(
    String repoOwner, String repoName, ReviewMode mode, int prNumber, String commitSha) {

  public ReviewKey {
    if (repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
      throw new IllegalArgumentException("repo owner/name must be non-blank");
    }
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(commitSha, "commitSha");
    if (commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
    if (prNumber < 0) {
      throw new IllegalArgumentException("prNumber must be non-negative: " + prNumber);
    }
    if (mode == ReviewMode.COMMIT && prNumber != 0) {
      throw new IllegalArgumentException("push/commit 形态无 PR 编号: " + prNumber);
    }
    if (mode == ReviewMode.ISSUE && prNumber <= 0) {
      // 夜间形态必须知道被评审的历史 PR；PR 形态允许 pr=0（本地 pre-PR 模式，05§3）
      throw new IllegalArgumentException(mode + " 形态需要正数 PR 编号: " + prNumber);
    }
  }

  /**
   * PR 形态键（既有行为）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param prNumber PR 编号
   * @param commitSha head sha
   * @return 账本键
   */
  public static ReviewKey pullRequest(
      String repoOwner, String repoName, int prNumber, String commitSha) {
    return new ReviewKey(repoOwner, repoName, ReviewMode.PULL_REQUEST, prNumber, commitSha);
  }

  /**
   * push 直推形态键（无 PR 号）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param commitSha head sha
   * @return 账本键
   */
  public static ReviewKey commit(String repoOwner, String repoName, String commitSha) {
    return new ReviewKey(repoOwner, repoName, ReviewMode.COMMIT, 0, commitSha);
  }

  /**
   * 夜间存量形态键（被评审对象是历史 PR，载体是 issue）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param prNumber 被评审的 PR 编号
   * @param commitSha PR head sha
   * @return 账本键
   */
  public static ReviewKey issue(String repoOwner, String repoName, int prNumber, String commitSha) {
    return new ReviewKey(repoOwner, repoName, ReviewMode.ISSUE, prNumber, commitSha);
  }
}
