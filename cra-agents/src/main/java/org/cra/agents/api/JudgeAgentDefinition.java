package org.cra.agents.api;

import java.util.Map;
import java.util.Objects;

/**
 * JudgeAgent 定义（07 T2.6；03§4.4 一次性终裁：INSUFFICIENT 仲裁 + important 级 REFUTED 抽查复核）。
 * 一次性——不做多轮辩论（Debate or Vote：仲裁不做辩论循环）；强模型档位由路由层决定（T2.9）。
 *
 * <p>预算对齐 03§6 速查表 JudgeAgent 行（≤16k 输入 / ≤1k 输出 / 2 轮）；上下文按案件界定（≤3 文件， 截取在编排器组装侧完成）。
 */
public final class JudgeAgentDefinition implements SubAgent<JudgeCaseInput> {

  /** 仲裁者角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("judge", "终裁仲裁员");

  private static final String TEMPLATE = "prompts/judge/arbitrate.md";

  /** 默认输入预算（03§6 JudgeAgent 行；agents.budget.judge.input-tokens 可覆盖）。 */
  public static final long DEFAULT_INPUT_TOKENS = 20_000;

  /** 默认输出预算（agents.budget.judge.output-tokens 可覆盖）。 */
  public static final long DEFAULT_OUTPUT_TOKENS = 1_500;

  private final TemplateSupport templates;

  /**
   * @param templates 模板装载器（组合根经 {@code AgentsAssembly} 注入）
   */
  public JudgeAgentDefinition(TemplateSupport templates) {
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  @Override
  public AgentRole role() {
    return ROLE;
  }

  @Override
  public AgentBrief buildBrief(JudgeCaseInput input) {
    String rendered =
        templates.render(
            TEMPLATE,
            Map.of(
                "case_reason",
                input.caseReason(),
                "untrusted_case",
                TemplateSupport.untrusted(renderCase(input)),
                "untrusted_verifier",
                TemplateSupport.untrusted(input.verifierVerdict()),
                "untrusted_context",
                input.caseContext().isBlank()
                    ? "（无附加上下文）"
                    : TemplateSupport.untrusted(input.caseContext()),
                "followup_section",
                ""));
    return TemplateSupport.briefOf(templates, TEMPLATE, rendered, ROLE, tools(), budget());
  }

  @Override
  public ToolWhitelist tools() {
    return ToolWhitelist.NONE;
  }

  /**
   * 强档（03§6 速查表 JudgeAgent 行：强；T2.9 路由落地后本档位从未回填，M3 验收 409 修复）。
   *
   * @return 强档
   */
  @Override
  public org.cra.agents.api.AgentTier modelTier() {
    return org.cra.agents.api.AgentTier.STRONG;
  }

  @Override
  public AgentBudget budget() {
    // AI Coding 放宽档（ADR-0015 §2.2 修订）：16k/1k → 20k/1.5k——案件上下文 ≤3 文件 + 调用点索引
    return new AgentBudget(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS, 2, 240_000);
  }

  private static String renderCase(JudgeCaseInput input) {
    StringBuilder sb = new StringBuilder();
    sb.append("id=")
        .append(input.findingId())
        .append("  category=")
        .append(input.category())
        .append("  severity=")
        .append(input.severity())
        .append('\n');
    sb.append("标题：").append(input.title()).append("\n\n");
    sb.append("描述：").append(input.description()).append("\n\n");
    sb.append("失败模式：").append(input.failureMode()).append("\n\n");
    sb.append("发现者证据：\n```\n").append(input.evidence()).append("\n```\n");
    if (!input.suggestion().isBlank()) {
      sb.append("建议：\n```\n").append(input.suggestion()).append("\n```\n");
    }
    return sb.toString();
  }
}
