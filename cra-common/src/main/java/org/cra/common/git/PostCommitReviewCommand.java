package org.cra.common.git;

/**
 * commit comment 发布命令（M4c push 直推形态，05§2.4；GitPlatformAdapter.postCommitReview 入参）。
 *
 * <p>commit comment 无行级锚点（GitHub 的 commit comment 只能锚定到 commit，不能锚定到 diff 行），因此本形态不发布 inline 评论：全部
 * finding 以详情列表进入正文，文末带 run 完成标记（05§1.3）。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param commitSha 被评审的 head sha（载体作用域）
 * @param body commit comment 正文（含全部 finding 详情与 run 完成标记）
 */
public record PostCommitReviewCommand(
    String repoOwner, String repoName, String commitSha, String body) {

  public PostCommitReviewCommand {
    if (repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
      throw new IllegalArgumentException("repo owner/name must be non-blank");
    }
    if (commitSha == null || commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body must be non-blank");
    }
  }
}
