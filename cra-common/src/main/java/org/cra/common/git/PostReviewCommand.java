package org.cra.common.git;

import java.util.List;

/**
 * 发布命令（GitPlatformAdapter.postReview 入参，05§2.2）：PR review 只装 inline 评论（body 固定 引导语），Summary 走 issue
 * comments 端点（可 PATCH 编辑，载体分离）。
 *
 * @param prNumber PR 编号
 * @param commitId 绑定的 head sha（inline 评论 commit_id）
 * @param reviewBody PR review 的 body 引导语（不能为空）
 * @param inlineComments inline 评论列表（锚定校验已通过的条目）
 * @param summaryBody Summary issue comment 正文（文末带 run 完成标记，05§1.3）
 */
public record PostReviewCommand(
    int prNumber,
    String commitId,
    String reviewBody,
    List<InlineComment> inlineComments,
    String summaryBody) {

  public PostReviewCommand {
    if (prNumber <= 0) {
      throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
    }
    if (commitId == null || commitId.isBlank()) {
      throw new IllegalArgumentException("commitId must be non-blank");
    }
    if (reviewBody == null || reviewBody.isBlank()) {
      throw new IllegalArgumentException("reviewBody must be non-blank（GitHub 不允许空 body）");
    }
    inlineComments = List.copyOf(inlineComments == null ? List.of() : inlineComments);
    if (summaryBody == null || summaryBody.isBlank()) {
      throw new IllegalArgumentException("summaryBody must be non-blank");
    }
  }
}
