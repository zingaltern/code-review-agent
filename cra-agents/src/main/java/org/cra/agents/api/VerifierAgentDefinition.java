package org.cra.agents.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * VerifierAgent 定义（07 T2.4；03§4.2 M2 形态：每文件批次 × 1 个强模型验证者，平衡式独立复核）。 纯定义：模板装载 + brief 组装；LLM
 * 执行与判定解析在 cra-core（10§2）。
 *
 * <p>预算对齐 03§6 速查表 VerifierAgent 行（代码类 ≤16k 输入 / ≤2k 输出 / 2 轮；context 类与调用点索引
 * 在同一输入预算内由编排器裁剪，不额外加预算）。反锚定是输出约束 + 机器侧质控信号（PASS/FAIL 入 trace，不阻断晋升）。
 */
public final class VerifierAgentDefinition implements SubAgent<VerificationBatchInput> {

  /** 验证者角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("verifier", "独立复核员");

  private static final String TEMPLATE = "prompts/verifier/balanced-review.md";

  /** 默认输入预算（03§6 VerifierAgent 行；agents.budget.verifier.input-tokens 可覆盖）。 */
  public static final long DEFAULT_INPUT_TOKENS = 16_000;

  /** 默认输出预算（含 refutation 对象；agents.budget.verifier.output-tokens 可覆盖）。 */
  public static final long DEFAULT_OUTPUT_TOKENS = 2_000;

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
                        + TemplateSupport.untrusted(input.followupContext()),
                "layer_contract_section",
                input.layerContractSection(),
                "untrusted_test_file_index",
                input.testFileIndex().isBlank()
                    ? "（本批无 test 类结论或测试清单不可得）"
                    : TemplateSupport.untrusted(input.testFileIndex())));
    return TemplateSupport.briefOf(templates, TEMPLATE, rendered, ROLE, tools(), budget());
  }

  @Override
  public ToolWhitelist tools() {
    return ToolWhitelist.NONE;
  }

  /**
   * 强档（03§6 速查表 VerifierAgent 行：M2 强 / M3 V1 强 + V2 异构；T2.9 路由落地后本档位 从未回填——验证层长期跑在 standard
   * 模型上，是拒真/复核能力不足的结构性原因，M3 验收 409 修复）。
   *
   * @return 强档
   */
  @Override
  public org.cra.agents.api.AgentTier modelTier() {
    return org.cra.agents.api.AgentTier.STRONG;
  }

  @Override
  public AgentBudget budget() {
    // AI Coding 放宽档（ADR-0015 §2.2 修订）：12k/1.5k → 16k/2k——调用点索引（≤1000 token）
    // 与跨层 caller 证据需要更多代码上下文，输出预算随判定润色同步上调
    return new AgentBudget(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS, 2, 240_000);
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
