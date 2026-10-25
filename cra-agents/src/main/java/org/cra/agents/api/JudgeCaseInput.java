package org.cra.agents.api;

import java.util.Objects;

/**
 * JudgeAgent 的输入（03§4.4：brief = finding + 验证者判定与证据 + 更大配额的涉事代码上下文）。 上下文按案件界定（≤3
 * 文件，03§6——截取由编排器完成，本记录只承载文本）。
 *
 * <p>不可信内容（case 各字段/verifierVerdict/caseContext）由定义渲染时 UNTRUSTED 定界（04§3.3，
 * 验证者输出同样不可信——04§4.3）；caseReason 为可信的情形说明（编排器生成）。
 *
 * @param findingId 被仲裁的 finding 标识
 * @param category 类别（小写）
 * @param severity 提议严重度（important|nit）
 * @param title 一句话标题
 * @param description 问题描述（含发现者论证）
 * @param failureMode 触发条件→错误结果
 * @param evidence 发现者证据
 * @param suggestion 修复建议，可空（空串归一）
 * @param verifierVerdict 复核员判定原文（verdict + rationale + 反证；不可信）
 * @param caseContext 涉事文件上下文（≤3 文件的全文/窗口，不可信）
 * @param caseReason 情形说明（可信）：仲裁（复核员不能判）或抽查复核（important 级被推翻，防错杀）
 */
public record JudgeCaseInput(
    String findingId,
    String category,
    String severity,
    String title,
    String description,
    String failureMode,
    String evidence,
    String suggestion,
    String verifierVerdict,
    String caseContext,
    String caseReason) {

  public JudgeCaseInput {
    Objects.requireNonNull(findingId, "findingId");
    if (findingId.isBlank()) {
      throw new IllegalArgumentException("findingId must be non-blank");
    }
    requireNonBlank(category, "category");
    requireNonBlank(severity, "severity");
    requireNonBlank(title, "title");
    requireNonBlank(description, "description");
    requireNonBlank(failureMode, "failureMode");
    requireNonBlank(evidence, "evidence");
    suggestion = suggestion == null ? "" : suggestion;
    requireNonBlank(verifierVerdict, "verifierVerdict");
    caseContext = caseContext == null ? "" : caseContext;
    requireNonBlank(caseReason, "caseReason");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
