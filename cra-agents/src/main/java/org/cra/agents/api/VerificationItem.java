package org.cra.agents.api;

import java.util.Objects;

/**
 * 验证批次内单条待复核结论（05§1.1 finding 全文的最小投影；发现者论证不剥离——04§4.2 "不假装盲， 靠输出约束反锚定"）。id 由编排器生成并原样回填
 * verdict（VerdictJsonCodec 按 id 匹配）。
 *
 * @param findingId finding 标识（f_ 前缀）
 * @param category 类别（小写：bug|security|test|convention|performance|context）
 * @param severity 提议严重度（important|nit）
 * @param title 一句话标题
 * @param description 问题描述（含发现者论证）
 * @param failureMode 触发条件→错误结果
 * @param evidence 发现者证据（行级引用）
 * @param suggestion 修复建议，可空（空串归一）
 */
public record VerificationItem(
    String findingId,
    String category,
    String severity,
    String title,
    String description,
    String failureMode,
    String evidence,
    String suggestion) {

  public VerificationItem {
    Objects.requireNonNull(findingId, "findingId");
    if (findingId.isBlank()) {
      throw new IllegalArgumentException("findingId must be non-blank");
    }
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(severity, "severity");
    requireNonBlank(title, "title");
    requireNonBlank(description, "description");
    requireNonBlank(failureMode, "failureMode");
    requireNonBlank(evidence, "evidence");
    suggestion = suggestion == null ? "" : suggestion;
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
