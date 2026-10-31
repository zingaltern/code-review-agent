package org.cra.agents.api;

import java.util.Map;
import java.util.Objects;

/**
 * Test/Convention/Perf 三专角色定义（T2.3，03§3 角色表 + 03§6 预算行）。 条件触发判定在编排器（ReviewStage）；本类只做模板装载与 brief
 * 组装。
 *
 * <p>预算（03§6，2026-10-31 预算翻倍）：Test 32k/8k/2 轮 read_file 标准档；Convention 16k/2k/1 轮 无工具 轻档； Perf
 * 32k/2k/2 轮 read_file 标准档（按 chunk）。
 */
public final class SpecialistReviewers {

  private SpecialistReviewers() {}

  /** TestReviewer 定义（测试缺口，category=test）。 */
  public static final class TestReviewer implements SubAgent<ReviewAgentInput> {

    // v1.3.2/ADR-0015：输出 1k→2k→4k（与 BugHunter 对齐）——test finding 粒度同 BH，1k 仅容纳 ~2 条，
    // M3 实测 90 观测中 11 次输出预算击穿（12%）导致该角色零产出
    /** 默认输入预算（03§6 TestReviewer 行；agents.budget.test-reviewer.input-tokens 可覆盖）。 */
    public static final long DEFAULT_INPUT_TOKENS = 32_000;

    // 2026-10-31 预算翻倍：输入 16k → 32k、输出 4k → 8k（ADR-0015 §2.2 放宽档之上；原 12k→16k）
    private static final AgentBudget BUDGET =
        new AgentBudget(DEFAULT_INPUT_TOKENS, 8_000, 2, 120_000);

    /** 角色。 */
    public static final AgentRole AGENT_ROLE = new AgentRole("test-reviewer", "测试缺口评审");

    private static final String TEMPLATE = "prompts/reviewer/test.md";

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public TestReviewer(TemplateSupport templates) {
      this.templates = Objects.requireNonNull(templates, "templates");
    }

    @Override
    public AgentRole role() {
      return AGENT_ROLE;
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
                  "untrusted_extra_section",
                  input.extraSection().isBlank()
                      ? "（无测试目录结构信息）"
                      : TemplateSupport.untrusted(input.extraSection()),
                  "review_policy_section",
                  input.reviewPolicySection().isBlank()
                      ? "（本仓库未配置 REVIEW.md 评审策略）"
                      : input.reviewPolicySection()));
      return TemplateSupport.briefOf(
          templates, TEMPLATE, rendered, AGENT_ROLE, tools(), budget(), modelTier());
    }

    @Override
    public ToolWhitelist tools() {
      return ToolWhitelist.READ_FILE;
    }

    @Override
    public AgentBudget budget() {
      return BUDGET;
    }
  }

  /** ConventionReviewer 定义（REVIEW.md 约定对照，category=convention；无工具、轻档）。 */
  public static final class ConventionReviewer implements SubAgent<ReviewAgentInput> {

    /** 预算（03§6 ConventionReviewer 行：无工具）。 */
    private static final AgentBudget BUDGET = new AgentBudget(16_000, 2_000, 1, 60_000);

    /** 角色。 */
    public static final AgentRole AGENT_ROLE = new AgentRole("convention-reviewer", "约定评审");

    private static final String TEMPLATE = "prompts/reviewer/convention.md";

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public ConventionReviewer(TemplateSupport templates) {
      this.templates = Objects.requireNonNull(templates, "templates");
    }

    @Override
    public AgentRole role() {
      return AGENT_ROLE;
    }

    @Override
    public AgentBrief buildBrief(ReviewAgentInput input) {
      // Conventions 清单属 base 分支 REVIEW.md（04§3.2 信任模型内可信内容），经 review_policy_section 注入，不定界
      String rendered =
          templates.render(
              TEMPLATE,
              Map.of(
                  "untrusted_pr_meta",
                  TemplateSupport.untrusted(input.prTitle() + "\n" + input.commitMessages()),
                  "untrusted_diff",
                  TemplateSupport.untrusted(input.diffText()),
                  "review_policy_section",
                  input.reviewPolicySection().isBlank()
                      ? "（本仓库未配置 REVIEW.md 评审策略）"
                      : input.reviewPolicySection()));
      return TemplateSupport.briefOf(
          templates, TEMPLATE, rendered, AGENT_ROLE, tools(), budget(), modelTier());
    }

    @Override
    public ToolWhitelist tools() {
      return ToolWhitelist.NONE;
    }

    @Override
    public AgentBudget budget() {
      return BUDGET;
    }

    @Override
    public AgentTier modelTier() {
      return AgentTier.LIGHT;
    }
  }

  /** PerfReviewer 定义（性能风险，category=performance；Deep 档按 chunk 扇出）。 */
  public static final class PerfReviewer implements SubAgent<ReviewAgentInput> {

    /** 预算（03§6 PerfReviewer 行）。 */
    private static final AgentBudget BUDGET = new AgentBudget(32_000, 2_000, 2, 120_000);

    /** 角色。 */
    public static final AgentRole AGENT_ROLE = new AgentRole("perf-reviewer", "性能评审");

    private static final String TEMPLATE = "prompts/reviewer/perf.md";

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public PerfReviewer(TemplateSupport templates) {
      this.templates = Objects.requireNonNull(templates, "templates");
    }

    @Override
    public AgentRole role() {
      return AGENT_ROLE;
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
                  "untrusted_extra_section",
                  input.extraSection().isBlank()
                      ? "（无热点标注）"
                      : TemplateSupport.untrusted(input.extraSection()),
                  "review_policy_section",
                  input.reviewPolicySection().isBlank()
                      ? "（本仓库未配置 REVIEW.md 评审策略）"
                      : input.reviewPolicySection(),
                  "scoped_paths_note",
                  TemplateSupport.scopedPathsNote(input.scopedPaths())));
      return TemplateSupport.briefOf(
          templates, TEMPLATE, rendered, AGENT_ROLE, tools(), budget(), modelTier());
    }

    @Override
    public ToolWhitelist tools() {
      return ToolWhitelist.READ_FILE;
    }

    @Override
    public AgentBudget budget() {
      return BUDGET;
    }
  }
}
