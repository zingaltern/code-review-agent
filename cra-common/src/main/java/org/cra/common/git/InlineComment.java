package org.cra.common.git;

import org.cra.common.finding.Side;

/**
 * 单条 inline 评论载荷（PR review comments 数组元素，05§2.2）。
 *
 * @param path 文件路径
 * @param line 行号（锚行）
 * @param side 行号侧（RIGHT=新代码）
 * @param body 评论正文（含转义后的 finding 文本 + 末位账本标记，05§1.3 规则 3）
 * @param findingId 对应 finding 标识（发布结果回填用）
 */
public record InlineComment(String path, int line, Side side, String body, String findingId) {

  public InlineComment {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("line must be positive: " + line);
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body must be non-blank");
    }
    if (findingId == null || findingId.isBlank()) {
      throw new IllegalArgumentException("findingId must be non-blank");
    }
  }
}
