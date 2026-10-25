package org.cra.common.git;

import java.time.Instant;

/**
 * 处置信号（06§1.1：对 bot 评论的 reply 标签 / reaction / 合并事件回填）。M0 仅占位类型， 采集在 M3 处置闭环落地（CommentSource
 * 实现返回空数组并声明"不采"是合法形态，11§4 第 5 条）。
 *
 * @param kind 信号类别（reply_tag / reaction / merge_backfill）
 * @param commentId 锚定的 bot 评论 id
 * @param occurredAt 信号发生时间
 */
public record DispositionSignal(String kind, long commentId, Instant occurredAt) {

  public DispositionSignal {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind must be non-blank");
    }
    if (commentId <= 0) {
      throw new IllegalArgumentException("commentId must be positive: " + commentId);
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt must be non-null");
    }
  }
}
