package org.cra.common.git;

import java.time.Instant;
import java.util.Objects;

/**
 * bot 评论原始条目（账本承载评论，02§5：已发布 finding、指纹、run 标识内嵌于评论体）。 过期收尾 PATCH 与处置采集（06§1.1）都锚定在这些评论上。
 *
 * @param id 评论 id（inline 与 issue 评论分属不同端点，id 空间独立，05§2.3）
 * @param kind inline（PR review comment）或 issue（Summary issue comment）
 * @param author 评论作者 login（bot 身份，05§2.3）
 * @param body 评论原文（未解析）
 * @param createdAt 创建时间
 * @param commitId inline 评论绑定的 commit sha（issue 评论为 null）
 */
public record LedgerComment(
    long id, String kind, String author, String body, Instant createdAt, String commitId) {

  /** inline 评论（PR review comment）。 */
  public static final String KIND_INLINE = "inline";

  /** issue comment（Summary 载体，05§2.2）。 */
  public static final String KIND_ISSUE = "issue";

  public LedgerComment {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive: " + id);
    }
    Objects.requireNonNull(kind, "kind");
    if (!KIND_INLINE.equals(kind) && !KIND_ISSUE.equals(kind)) {
      throw new IllegalArgumentException("kind must be inline|issue: " + kind);
    }
    Objects.requireNonNull(author, "author");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(createdAt, "createdAt");
    commitId = commitId == null ? "" : commitId;
  }
}
