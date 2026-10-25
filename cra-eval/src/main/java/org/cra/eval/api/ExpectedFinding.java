package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 单条期望发现（06§3 断言器口径：位置 ±5 行 + 类别 + 断言关键词，全部满足才算命中）。
 *
 * @param file 相对仓库根的文件路径（与 CandidateFinding.file 同一坐标系）
 * @param line 期望行号（buggy 侧 RIGHT 行，±5 行容差）
 * @param category 期望类别（FindingCategory 小写名：bug|security|test|convention|performance|context）
 * @param keywords 断言关键词（全部大小写不敏感出现于 finding 正文才算命中；空列表 = 不限关键词）
 * @param note 标注备注（人工确认记录），可空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpectedFinding(
    @JsonProperty("file") String file,
    @JsonProperty("line") int line,
    @JsonProperty("category") String category,
    @JsonProperty("keywords") List<String> keywords,
    @JsonProperty("note") String note) {

  public static final int LINE_TOLERANCE = 5;

  public ExpectedFinding {
    if (file == null || file.isBlank()) {
      throw new IllegalArgumentException("expected.file must be non-blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("expected.line must be positive: " + line);
    }
    if (category == null || category.isBlank()) {
      throw new IllegalArgumentException("expected.category must be non-blank");
    }
    keywords = List.copyOf(keywords == null ? List.of() : keywords);
    note = note == null ? "" : note;
  }
}
