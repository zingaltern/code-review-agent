package org.cra.agents.api;

import java.util.Map;
import java.util.Objects;

/**
 * SecurityReviewer 独立安全 pass（T2.3，03§3 角色表 + 03§6 v1.2 修正：按 chunk 扇出 + 窗口上下文， 仅对敏感路径注入该 chunk
 * 文件全文——独享全量 brief 超 2 倍预算方案已废弃）。 预算对齐 03§6（≤48k 输入 / ≤4k 输出 / 3 轮 / read_file+grep(scoped) / 强档）。
 */
public final class SecurityReviewerDefinition implements SubAgent<ReviewAgentInput> {

  /** 预算（03§6 SecurityReviewer 行）。 */
  private static final AgentBudget BUDGET = new AgentBudget(48_000, 4_000, 3, 240_000);

  /** 角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("security-reviewer", "安全评审");

  private static final String TEMPLATE = "prompts/reviewer/security.md";

  private final TemplateSupport templates;

  /**
   * @param templates 模板装载器
   */
  public SecurityReviewerDefinition(TemplateSupport templates) {
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  @Override
  public AgentRole role() {
    return ROLE;
  }

  @Override
  public AgentBrief buildBrief(ReviewAgentInput input) {
    String rendered =
        templates.render(
            TEMPLATE,
            Map.of(
                "untrusted_pr_meta",
                TemplateSupport.untrusted(input.prTitle() + "\n" + input.commitMessages()),
                "untrusted_diff",
                TemplateSupport.untrusted(input.diffText()),
                "untrusted_l1_context",
                input.windowText().isBlank()
                    ? "（无附加上下文）"
                    : TemplateSupport.untrusted(input.windowText()),
                "untrusted_slices",
                input.slicesText().isBlank()
                    ? "（无切片）"
                    : TemplateSupport.untrusted(input.slicesText()),
                "untrusted_extra_section",
                input.extraSection().isBlank()
                    ? "（本 chunk 无敏感文件全文/依赖清单变更）"
                    : TemplateSupport.untrusted(input.extraSection()),
                "review_policy_section",
                input.reviewPolicySection().isBlank()
                    ? "（本仓库未配置 REVIEW.md 评审策略）"
                    : input.reviewPolicySection(),
                "scoped_paths_note",
                scopedPathsNote(input)));
    return TemplateSupport.briefOf(
        templates, TEMPLATE, rendered, ROLE, tools(), budget(), modelTier());
  }

  @Override
  public ToolWhitelist tools() {
    // 03§2.3：SecurityReviewer 加 grep_pattern(scoped)
    return ToolWhitelist.READ_FILE_AND_GREP;
  }

  @Override
  public AgentBudget budget() {
    return BUDGET;
  }

  @Override
  public AgentTier modelTier() {
    return AgentTier.STRONG;
  }

  private static String scopedPathsNote(ReviewAgentInput input) {
    return TemplateSupport.scopedPathsNote(
        input.scopedPaths(), ToolWhitelist.READ_FILE_NAME + "/" + ToolWhitelist.GREP_PATTERN_NAME);
  }
}
