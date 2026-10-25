package org.cra.common.git;

import java.util.Objects;

/**
 * 账本中的 finding 级标记（05§1.3：每条 inline bot 评论末尾的 cra: 标记解析结果）。
 *
 * @param fingerprint 指纹 v2（sha256(file|category|anchor_line_content_hash|anchor_context_hash)， 不含
 *     LLM 文本与绝对行号，02§4.1）
 * @param file 文件路径
 * @param line 锚行号
 * @param category 类别小写（bug/security/test/convention/performance/context）
 * @param findingId finding 标识
 * @param runId 发布该 finding 的 run 标识
 * @param commitSha 发布时的 head sha
 * @param commentId 承载该标记的评论 id（inline 评论）
 */
public record LedgerFindingMarker(
    String fingerprint,
    String file,
    int line,
    String category,
    String findingId,
    String runId,
    String commitSha,
    long commentId) {

  public LedgerFindingMarker {
    Objects.requireNonNull(fingerprint, "fingerprint");
    if (fingerprint.isBlank() || !fingerprint.matches("[0-9a-f]+")) {
      throw new IllegalArgumentException("fingerprint must be hex: " + fingerprint);
    }
    Objects.requireNonNull(file, "file");
    if (file.isBlank()) {
      throw new IllegalArgumentException("file must be non-blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("line must be positive: " + line);
    }
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(findingId, "findingId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(commitSha, "commitSha");
    if (commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
  }
}
