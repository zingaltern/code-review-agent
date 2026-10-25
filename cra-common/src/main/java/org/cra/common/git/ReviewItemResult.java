package org.cra.common.git;

import java.util.Objects;
import org.cra.common.finding.AnchorStatus;

/**
 * 单条 finding 的发布条目结果（PrReviewResult.items 元素，05§2.2）。
 *
 * @param findingId 对应 finding 标识
 * @param path 文件路径（位置非法时为 null）
 * @param line 行号（位置非法时为 -1）
 * @param publishStatus 发布状态（PUBLISHED/SUMMARY_FALLBACK/FAILED）
 * @param anchorStatus 锚定校验结果（INLINE/SUMMARY_FALLBACK）
 * @param commentId 已发布评论 id（inline 评论 id，未发布为 null）
 * @param errorMessage 失败原因，无失败为 null
 */
public record ReviewItemResult(
    String findingId,
    String path,
    int line,
    PublishStatus publishStatus,
    AnchorStatus anchorStatus,
    Long commentId,
    String errorMessage) {

  public ReviewItemResult {
    Objects.requireNonNull(findingId, "findingId");
    Objects.requireNonNull(publishStatus, "publishStatus");
    Objects.requireNonNull(anchorStatus, "anchorStatus");
    if (line <= 0) {
      line = -1;
    }
    errorMessage = errorMessage == null ? "" : errorMessage;
  }
}
