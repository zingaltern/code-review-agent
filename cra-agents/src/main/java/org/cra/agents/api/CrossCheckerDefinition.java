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
 * <p>预算对齐 03§6 速查表 CrossChecker 行（≤16k 输入 = diff 精简视图 ~8k + 变更史摘要 ≤4k + 产品段 ≤2k；≤1.5k 输出；2
 * 轮；标准档；read_file(scoped)）。
 */
public final class CrossCheckerDefinition implements SubAgent<ReviewAgentInput> {

  /** 预算（03§6 CrossChecker 行）。 */
  private static final AgentBudget BUDGET = new AgentBudget(16_000, 1_500, 2, 240_000);

  /** 角色（kebab-case，05§1.1）。 */
  public static final AgentRole ROLE = new AgentRole("cross-checker", "上下文对齐评审");

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
    int splitAt = rendered.indexOf("\n# User\n");
    if (splitAt < 0) {
      throw new IllegalStateException("cross-checker.md 模板缺少 '# User' 分段");
    }
    return new AgentBrief(
        ROLE,
        rendered.substring(0, splitAt),
        rendered.substring(splitAt + 1),
        templates.versionOf(TEMPLATE),
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

  /** 标准档（03§6 CrossChecker 行：标准）；modelTier 默认即 STANDARD，此处显式覆盖以对齐速查表。 */
  @Override
  public AgentTier modelTier() {
    return AgentTier.STANDARD;
  }

  private static String scopedPathsNote(ReviewAgentInput input) {
    if (input.scopedPaths().isEmpty()) {
      return "（本代理无工具白名单：全部上下文已内联，不可请求工具）";
    }
    return "read_file 仅可读取以下文件（相对仓库根，越界请求将被拒绝并终止执行）：\n"
        + String.join("\n", input.scopedPaths().stream().sorted().toList());
  }
}
