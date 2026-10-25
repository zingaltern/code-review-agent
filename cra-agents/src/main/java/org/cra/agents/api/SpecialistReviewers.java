package org.cra.agents.api;

import java.util.Map;
import java.util.Objects;

/**
 * Test/Convention/Perf 三专角色定义（T2.3，03§3 角色表 + 03§6 预算行）。 条件触发判定在编排器（ReviewStage）；本类只做模板装载与 brief
 * 组装。
 *
 * <p>预算（03§6）：Test 12k/1k/2 轮 read_file 标准档；Convention 8k/1k/1 轮 无工具 轻档； Perf 16k/1k/2 轮 read_file
 * 标准档（按 chunk）。
 */
public final class SpecialistReviewers {

  private SpecialistReviewers() {}

  /** TestReviewer 定义（测试缺口，category=test）。 */
  public static final class TestReviewer implements SubAgent<ReviewAgentInput> {

    /** 预算（03§6 TestReviewer 行）。 */
    private static final AgentBudget BUDGET = new AgentBudget(12_000, 1_000, 2, 120_000);

    /** 角色。 */
    public static final AgentRole ROLE = new AgentRole("test-reviewer", "测试缺口评审");

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public TestReviewer(TemplateSupport templates) {
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
              "prompts/reviewer/test.md",
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
      int splitAt = rendered.indexOf("\n# User\n");
      if (splitAt < 0) {
        throw new IllegalStateException("test.md 模板缺少 '# User' 分段");
      }
      return new AgentBrief(
          ROLE,
          rendered.substring(0, splitAt),
          rendered.substring(splitAt + 1),
          templates.versionOf("prompts/reviewer/test.md"),
          tools(),
          budget());
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
    private static final AgentBudget BUDGET = new AgentBudget(8_000, 1_000, 1, 60_000);

    /** 角色。 */
    public static final AgentRole ROLE = new AgentRole("convention-reviewer", "约定评审");

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public ConventionReviewer(TemplateSupport templates) {
      this.templates = Objects.requireNonNull(templates, "templates");
    }

    @Override
    public AgentRole role() {
      return ROLE;
    }

    @Override
    public AgentBrief buildBrief(ReviewAgentInput input) {
      // Conventions 清单属 base 分支 REVIEW.md（04§3.2 信任模型内可信内容），经 review_policy_section 注入，不定界
      String rendered =
          templates.render(
              "prompts/reviewer/convention.md",
              Map.of(
                  "untrusted_pr_meta",
                  TemplateSupport.untrusted(input.prTitle() + "\n" + input.commitMessages()),
                  "untrusted_diff",
                  TemplateSupport.untrusted(input.diffText()),
                  "review_policy_section",
                  input.reviewPolicySection().isBlank()
                      ? "（本仓库未配置 REVIEW.md 评审策略）"
                      : input.reviewPolicySection()));
      int splitAt = rendered.indexOf("\n# User\n");
      if (splitAt < 0) {
        throw new IllegalStateException("convention.md 模板缺少 '# User' 分段");
      }
      return new AgentBrief(
          ROLE,
          rendered.substring(0, splitAt),
          rendered.substring(splitAt + 1),
          templates.versionOf("prompts/reviewer/convention.md"),
          tools(),
          budget());
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
    private static final AgentBudget BUDGET = new AgentBudget(16_000, 1_000, 2, 120_000);

    /** 角色。 */
    public static final AgentRole ROLE = new AgentRole("perf-reviewer", "性能评审");

    private final TemplateSupport templates;

    /**
     * @param templates 模板装载器
     */
    public PerfReviewer(TemplateSupport templates) {
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
              "prompts/reviewer/perf.md",
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
                      : input.reviewPolicySection()));
      int splitAt = rendered.indexOf("\n# User\n");
      if (splitAt < 0) {
        throw new IllegalStateException("perf.md 模板缺少 '# User' 分段");
      }
      return new AgentBrief(
          ROLE,
          rendered.substring(0, splitAt),
          rendered.substring(splitAt + 1),
          templates.versionOf("prompts/reviewer/perf.md"),
          tools(),
          budget());
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
