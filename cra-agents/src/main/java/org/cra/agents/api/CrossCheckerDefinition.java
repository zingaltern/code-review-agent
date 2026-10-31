package org.cra.agents.api;

import java.util.Map;
import java.util.Objects;

/**
 * CrossChecker 定义（07 M4f-lite；03§3.1 两模式：A 隐含假设核对 / B 嗅探检查执行）。
 *
 * <p>定位：检查「变更的隐含假设 vs 外部证据的矛盾」——证据主体在代码之外（上下游变更史 + REVIEW.md 的 Product context / Known incidents /
 * Sniff tests 段），diff 仍参与假设推导。输出 category=context（05§1.1 枚举已有；默认 important
 * 档），走与其他角色相同的验证晋升管线——cross check 的发现同样要过 double check，不享有免检（03§3.1）。
 *
 * <p>信任与隔离（03§3.1）：REVIEW.md 三个产品段经 base 分支读取 + owner 审批信任模型（04§3.2），渲染时不定界； 上下游 commit message
 * 属不可信输入，UNTRUSTED 定界（04§3.3）。brief 槽位映射：diffText = 精简视图， extraSection = 变更史（不可信），
 * reviewPolicySection = 产品段（可信）。
 *
 * <p>预算对齐 03§6 速查表 CrossChecker 行（≤32k 输入；材料分项沿用 diff 精简视图 ~8k + 变更史摘要 ≤4k + 产品段 ≤2k，2026-10-31
 * 预算翻倍后组合占比放宽；≤8k 输出；2 轮；标准档；read_file(scoped)）。
 */
public final class CrossCheckerDefinition implements SubAgent<ReviewAgentInput> {

  /** scoped 路径清单上限（避免清单挤占 brief 输入预算）。 */
  private static final int MAX_SCOPED_PATHS = 40;

  /** 预算（03§6 CrossChecker 行）。 */
  private static final AgentBudget BUDGET =
      // v1.3.2：输出 1.5k→4k——context finding 的 JSON（含矛盾来源引用）每条 ≈700-1k token，
      // M3 官方 L3 实测 cross-checker 输出 2122/2255 被 1.5k 截断（budget_exceeded，0 产出）
      new AgentBudget(32_000, 8_000, 2, 240_000);

  /** 角色（kebab-case，05§1.1）。 */
  public static final AgentRole AGENT_ROLE = new AgentRole("cross-checker", "上下文对齐评审");

  private static final String TEMPLATE = "prompts/reviewer/cross-checker.md";

  private final TemplateSupport templates;

  /**
   * @param templates 模板装载器（组合根经 {@code AgentsAssembly} 注入）
   */
  public CrossCheckerDefinition(TemplateSupport templates) {
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
                "untrusted_history",
                input.extraSection().isBlank()
                    ? "（模式 A 未启用，或上游/下游路径近期变更史不可得：无本地仓库/无命中 commit——降级声明，05§2.1）"
                    : TemplateSupport.untrusted(input.extraSection()),
                "trusted_context_sections",
                input.reviewPolicySection().isBlank()
                    ? "（REVIEW.md 未配置 Product context / Known incidents / Sniff tests 段落，相应模式跳过）"
                    : input.reviewPolicySection(),
                "scoped_paths_note",
                scopedPathsNote(input)));
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

  /** 标准档（03§6 CrossChecker 行：标准）；modelTier 默认即 STANDARD，此处显式覆盖以对齐速查表。 */
  @Override
  public AgentTier modelTier() {
    return AgentTier.STANDARD;
  }

  private static String scopedPathsNote(ReviewAgentInput input) {
    return TemplateSupport.scopedPathsNote(
        input.scopedPaths(), ToolWhitelist.READ_FILE_NAME, MAX_SCOPED_PATHS);
  }
}
