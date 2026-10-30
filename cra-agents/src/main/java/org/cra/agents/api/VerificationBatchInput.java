package org.cra.agents.api;

import java.util.List;
import org.cra.common.text.Texts;

/**
 * VerifierAgent 的输入（03§4.2：批处理单元 = 同一文件的候选 finding 合并为一个验证 brief）。 v1.3.1 分型组装：代码类注入文件全文 +
 * 切片；context 类额外注入被引矛盾证据原文（{@code citedEvidence}） 与 REVIEW.md 对应条目（{@code policySections}）——context
 * 类证据在代码之外，不注入则验证层失明。
 *
 * <p>受控补查（03§4.2 判定要求第三条）：第一轮 {@code followupContext} 为空串；复核员输出 INSUFFICIENT_EVIDENCE 且给出
 * followup_file 后，编排器取该文件全文注入发起第二轮（至多一次，03§6 轮次 2）。
 *
 * <p>不可信内容（findings/codeContext/citedEvidence/followupContext）由定义渲染时 UNTRUSTED 定界
 * （04§3.3）；policySections 来自 base 分支 REVIEW.md，属信任模型内可信内容（04§3.2）。
 *
 * @param file 批次文件路径（批处理键）
 * @param items 待复核结论（同文件合并批次，≥1 条）
 * @param codeContext 所在文件全文 + 相关切片（不可信）
 * @param citedEvidence context 类结论的被引证据原文（commit diff 节选等；不可信；无则为空串）
 * @param policySections REVIEW.md 证据要求段 + Product context/Known incidents 相关条目（可信）
 * @param followupContext 补查文件全文（不可信；第一轮为空串）
 * @param testFileIndex 仓库测试文件清单（仅 test 类批次；不可信；无则空串）
 * @param layerContractSection 分层核对契约（编排器注入的可信指令段；仅调用点索引可用时非空，04§2.6）
 */
public record VerificationBatchInput(
    String file,
    List<VerificationItem> items,
    String codeContext,
    String citedEvidence,
    String policySections,
    String followupContext,
    String testFileIndex,
    String layerContractSection) {

  public VerificationBatchInput {
    Texts.requireNonBlank(file, "file");
    items = List.copyOf(items == null ? List.of() : items);
    if (items.isEmpty()) {
      throw new IllegalArgumentException("items must be non-empty");
    }
    codeContext = codeContext == null ? "" : codeContext;
    citedEvidence = citedEvidence == null ? "" : citedEvidence;
    policySections = policySections == null ? "" : policySections;
    followupContext = followupContext == null ? "" : followupContext;
    testFileIndex = testFileIndex == null ? "" : testFileIndex;
    layerContractSection = layerContractSection == null ? "" : layerContractSection;
  }

  /**
   * 兼容构造（无分层核对契约）。
   *
   * @param file 批次文件路径
   * @param items 待复核结论
   * @param codeContext 所在文件全文 + 相关切片（不可信）
   * @param citedEvidence context 类结论的被引证据原文（不可信）
   * @param policySections REVIEW.md 证据要求段（可信）
   * @param followupContext 补查文件全文（不可信；第一轮为空串）
   * @param testFileIndex 仓库测试文件清单（不可信；无则空串）
   */
  public VerificationBatchInput(
      String file,
      List<VerificationItem> items,
      String codeContext,
      String citedEvidence,
      String policySections,
      String followupContext,
      String testFileIndex) {
    this(
        file,
        items,
        codeContext,
        citedEvidence,
        policySections,
        followupContext,
        testFileIndex,
        "");
  }

  /** 兼容构造（无测试文件清单）。 */
  public VerificationBatchInput(
      String file,
      List<VerificationItem> items,
      String codeContext,
      String citedEvidence,
      String policySections,
      String followupContext) {
    this(file, items, codeContext, citedEvidence, policySections, followupContext, "");
  }
}
