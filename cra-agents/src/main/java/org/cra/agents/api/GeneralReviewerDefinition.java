package org.cra.agents.api;

import java.util.Objects;

/**
 * M0 单一评审子代理定义（07 T0.4；04§4.1 简化版——M2 起 BugHunter 四视角取代）。 纯定义：模板装载 + brief 组装（UNTRUSTED
 * 定界从第一天内建，04§3.3）；LLM 循环在 cra-core。 预算对齐 03§6 速查表 BugHunter 行（≤24k 输入 / ≤2k 输出 / 3 轮）。
 */
public final class GeneralReviewerDefinition implements SubAgent<GeneralReviewInput> {

  /** M0 通用评审角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("general-reviewer", "通用评审");

  private final TemplateSupport templates;

  /**
   * @param templates 模板装载器（组合根经 {@code AgentsAssembly} 注入）
   */
  public GeneralReviewerDefinition(TemplateSupport templates) {
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  @Override
  public AgentRole role() {
    return ROLE;
  }

  @Override
  public AgentBrief buildBrief(GeneralReviewInput input) {
    String rendered =
        templates.render(
            "prompts/reviewer/general.md",
            java.util.Map.of(
                "untrusted_pr_meta",
                    TemplateSupport.untrusted(input.prTitle() + "\n" + input.commitMessages()),
                "untrusted_diff", TemplateSupport.untrusted(input.diffText()),
                "review_policy_section",
                    input.reviewPolicySection().isBlank()
                        ? "（本仓库未配置 REVIEW.md 评审策略）"
                        : input.reviewPolicySection()));
    int splitAt = rendered.indexOf("\n# User\n");
    if (splitAt < 0) {
      throw new IllegalStateException("general.md 模板缺少 '# User' 分段");
    }
    return new AgentBrief(
        ROLE,
        rendered.substring(0, splitAt),
        rendered.substring(splitAt + 1),
        templates.versionOf("prompts/reviewer/general.md"),
        ToolWhitelist.NONE,
        new AgentBudget(24_000, 2_000, 3, 240_000));
  }

  @Override
  public ToolWhitelist tools() {
    return ToolWhitelist.NONE;
  }

  @Override
  public AgentBudget budget() {
    return new AgentBudget(24_000, 2_000, 3, 240_000);
  }
}
