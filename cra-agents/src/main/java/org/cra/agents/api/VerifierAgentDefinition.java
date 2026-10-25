package org.cra.agents.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * VerifierAgent 定义（07 T2.4；03§4.2 M2 形态：每文件批次 × 1 个强模型验证者，平衡式独立复核）。 纯定义：模板装载 + brief 组装；LLM
 * 执行与判定解析在 cra-core（10§2，与 GeneralReviewerDefinition 同构）。
 *
 * <p>预算对齐 03§6 速查表 VerifierAgent 行（代码类 ≤12k 输入 / ≤1.5k 输出 / 2 轮；context 类分型组装 ≤14k
 * 由编排器在组装侧裁剪）。反锚定是输出约束 + 机器侧质控信号（PASS/FAIL 入 trace，不阻断晋升）。
 */
public final class VerifierAgentDefinition implements SubAgent<VerificationBatchInput> {

  /** 验证者角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("verifier", "独立复核员");

  private static final String TEMPLATE = "prompts/verifier/balanced-review.md";

  private final TemplateSupport templates;

  /**
   * @param templates 模板装载器（组合根经 {@code AgentsAssembly} 注入）
   */
  public VerifierAgentDefinition(TemplateSupport templates) {
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  @Override
  public AgentRole role() {
    return ROLE;
  }

  @Override
  public AgentBrief buildBrief(VerificationBatchInput input) {
    String rendered =
        templates.render(
            TEMPLATE,
            Map.of(
                "untrusted_findings",
                TemplateSupport.untrusted(renderFindings(input.items())),
                "untrusted_code_context",
                input.codeContext().isBlank()
                    ? "（文件内容不可得）"
                    : TemplateSupport.untrusted(input.codeContext()),
                "untrusted_cited_evidence",
                input.citedEvidence().isBlank()
                    ? "（无）"
                    : TemplateSupport.untrusted(input.citedEvidence()),
                "policy_sections",
                input.policySections().isBlank()
                    ? "（本仓库未配置 REVIEW.md 证据要求）"
                    : input.policySections(),
                "followup_section",
                input.followupContext().isBlank()
                    ? ""
                    : "## 补查材料（你申请的 followup 文件全文）\n"
                        + TemplateSupport.untrusted(input.followupContext())));
    int splitAt = rendered.indexOf("\n# User\n");
    if (splitAt < 0) {
      throw new IllegalStateException("balanced-review.md 模板缺少 '# User' 分段");
    }
    return new AgentBrief(
        ROLE,
        rendered.substring(0, splitAt),
        rendered.substring(splitAt + 1),
        templates.versionOf(TEMPLATE),
        ToolWhitelist.NONE,
        budget());
  }

  @Override
  public ToolWhitelist tools() {
    return ToolWhitelist.NONE;
  }

  @Override
  public AgentBudget budget() {
    return new AgentBudget(12_000, 1_500, 2, 240_000);
  }

  private static String renderFindings(List<VerificationItem> items) {
    StringBuilder sb = new StringBuilder();
    for (VerificationItem item : items) {
      sb.append("### id=")
          .append(item.findingId())
          .append("  category=")
          .append(item.category())
          .append("  severity=")
          .append(item.severity())
          .append('\n');
      sb.append("标题：").append(item.title()).append("\n\n");
      sb.append("描述：").append(item.description()).append("\n\n");
      sb.append("失败模式：").append(item.failureMode()).append("\n\n");
      sb.append("发现者证据：\n```\n").append(item.evidence()).append("\n```\n");
      if (!item.suggestion().isBlank()) {
        sb.append("建议：\n```\n").append(item.suggestion()).append("\n```\n");
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
