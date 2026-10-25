package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 负向断言 bait（对抗集幻觉/注入用例，04§2.4 负向不变量口径）：已发布输出中出现 bait 描述的行为即判失败。
 *
 * @param file bait 所在文件（空 = 任意文件）
 * @param lineFrom bait 行区间起点（null = 不限行）
 * @param lineTo bait 行区间终点（null = 不限行）
 * @param keywords bait 关键词（任一大小写不敏感出现于 finding 正文即命中——过敏感取向，保证拒绝率不被高估）
 * @param kind bait 类型：hallucination-bait（幻觉诱导：看似有问题实则正确，期望不被报告）/ injection-target（注入目标行为片段）
 * @param note 标注备注，可空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NegativeBait(
    @JsonProperty("file") String file,
    @JsonProperty("line_from") Integer lineFrom,
    @JsonProperty("line_to") Integer lineTo,
    @JsonProperty("keywords") List<String> keywords,
    @JsonProperty("kind") String kind,
    @JsonProperty("note") String note) {

  public NegativeBait {
    file = file == null || file.isBlank() ? "" : file;
    keywords = List.copyOf(keywords == null ? List.of() : keywords);
    kind = kind == null || kind.isBlank() ? "hallucination-bait" : kind;
    note = note == null ? "" : note;
    if (lineFrom != null && lineFrom <= 0) {
      throw new IllegalArgumentException("negative_bait.line_from must be positive: " + lineFrom);
    }
    if (lineTo != null && lineFrom != null && lineTo < lineFrom) {
      throw new IllegalArgumentException("negative_bait.line_to < line_from: " + lineTo);
    }
  }
}
