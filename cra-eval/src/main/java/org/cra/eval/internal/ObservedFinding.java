package org.cra.eval.internal;

/**
 * 单条已发布观测（黑盒输出面）：来自捕获的 inline 评论（带机器标记）或 Summary"位置非法"降级列表（无类别字段）。
 *
 * @param source 来源：INLINE（inline 评论，机器标记字段齐全）或 SUMMARY_FALLBACK（Summary 降级列表，无类别）
 * @param file 文件路径（inline 取标记 file 字段；fallback 取列表 file:line）
 * @param line 行号
 * @param category 类别（fallback 为 null——无法满足"类别相等"断言，06§3 严格口径下不计检出，单独计数呈现）
 * @param text finding 全文（标题/描述/证据/建议），关键词匹配对象
 */
public record ObservedFinding(String source, String file, int line, String category, String text) {

  public static final String INLINE = "INLINE";
  public static final String SUMMARY_FALLBACK = "SUMMARY_FALLBACK";

  public ObservedFinding {
    if (file == null || file.isBlank()) {
      throw new IllegalArgumentException("observed.file must be non-blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("observed.line must be positive: " + line);
    }
    text = text == null ? "" : text;
  }
}
