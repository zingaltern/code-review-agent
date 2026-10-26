package org.cra.common.git;

import java.time.Instant;
import java.util.Objects;

/**
 * bot 评论原始条目（账本承载评论，02§5：已发布 finding、指纹、run 标识内嵌于评论体）。 过期收尾 PATCH 与处置采集（06§1.1）都锚定在这些评论上。
 *
 * @param id 评论 id（inline / commit / issue 评论分属不同端点，id 空间独立，05§2.3）
 * @param kind inline（PR review comment）、commit（commit comment）或 issue（Summary / 夜间 issue 载体）
 * @param author 评论作者 login（bot 身份，05§2.3）
 * @param body 评论原文（未解析）
 * @param createdAt 创建时间
 * @param commitId inline / commit 评论绑定的 commit sha（issue 载体为 null）
 */
public record LedgerComment(
    long id, String kind, String author, String body, Instant createdAt, String commitId) {

  /** inline 评论（PR review comment）。 */
  public static final String KIND_INLINE = "inline";

  /** issue comment（Summary 载体，05§2.2）/ 夜间 issue 载体（05§2.5）。 */
  public static final String KIND_ISSUE = "issue";

  /** commit comment（push 直推载体，05§2.4）。 */
  public static final String KIND_COMMIT = "commit";

  public LedgerComment {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive: " + id);
    }
    Objects.requireNonNull(kind, "kind");
    if (!KIND_INLINE.equals(kind) && !KIND_ISSUE.equals(kind) && !KIND_COMMIT.equals(kind)) {
      throw new IllegalArgumentException("kind must be inline|issue|commit: " + kind);
    }
    Objects.requireNonNull(author, "author");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(createdAt, "createdAt");
    commitId = commitId == null ? "" : commitId;
  }
}
