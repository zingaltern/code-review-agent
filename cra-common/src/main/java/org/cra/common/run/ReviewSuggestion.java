package org.cra.common.run;

import java.util.Objects;

/**
 * 单条评审建议（本地 pre-PR 报告的"建议清单"条目，02§5 本地形态；T2.10）。 与 Finding 的解耦快照：编排器聚合后的最终形态（含 pre-existing 判级），CLI
 * 报告只读该快照， 不回查 finding 全模型。
 *
 * @param severity 展示档（IMPORTANT | NIT | PRE-EXISTING）
 * @param title 标题
 * @param file 文件路径
 * @param line 行号（1 基）
 * @param description 描述（失败模式含于正文）
 * @param suggestion 修改建议（可为空文本）
 */
public record ReviewSuggestion(
    String severity, String title, String file, int line, String description, String suggestion) {

  public ReviewSuggestion {
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(file, "file");
    if (line <= 0) {
      throw new IllegalArgumentException("line must be positive: " + line);
    }
    description = description == null ? "" : description;
    suggestion = suggestion == null ? "" : suggestion;
  }
}
