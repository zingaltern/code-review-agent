package org.cra.common.git;

import java.util.List;
import java.util.Optional;

/**
 * 发布结果（GitPlatformAdapter.postReview 返回值，05§2.2）。
 *
 * @param prNumber PR 编号
 * @param reviewId PR review id（inline 全部失败时可能缺省）
 * @param summaryCommentId Summary issue comment id（Summary 发布失败时缺省——run 无 DONE 标记， 重跑走指纹级补发，02§4.1）
 * @param items 逐条结果（顺序与 PostReviewCommand.inlineComments 一致，另有降级条目）
 */
public record PrReviewResult(
    int prNumber,
    Optional<String> reviewId,
    Optional<Long> summaryCommentId,
    List<ReviewItemResult> items) {

  public PrReviewResult {
    // 0 = 本地 pre-PR 模式（postReview 为 no-op 确认，无平台 PR）；CI 形态恒正数
    if (prNumber < 0) {
      throw new IllegalArgumentException("prNumber must be non-negative: " + prNumber);
    }
    items = List.copyOf(items == null ? List.of() : items);
  }
}
