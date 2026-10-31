package org.cra.agents.api;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * BugHunter 视角化评审子代理定义（T2.2，03§2.2 视角隔离：同角色多实例用不同视角系统提示词； 04§4.1 模板骨架）。纯定义：模板装载 + brief 组装；LLM 循环在
 * cra-core （{@code AgentRunner} 隔离运行时）。预算与工具对齐 03§6 速查表 BugHunter 行（≤24k 输入 / v1.3.2 起 ≤4k 输出 / 3 轮
 * / read_file(scoped)）。
 */
public final class BugHunterDefinition implements SubAgent<ReviewAgentInput> {

  /** BugHunter 视角（03§2.2 四视角表；id 进角色标识与模板路径）。 */
  public enum Perspective {
    /** 边界条件：空/null/越界/Off-by-one/类型截断——输入校验类 */
    BOUNDARY("boundary", "边界条件"),
    /** 并发与竞态：共享可变状态/锁粒度/事务边界——时序类 */
    CONCURRENCY("concurrency", "并发与竞态"),
    /** 数据流追踪：输入→变换→输出断裂点——逻辑类 */
    DATAFLOW("dataflow", "数据流正确性"),
    /** 资源生命周期：泄漏/未关闭/超时/重试风暴——运维类 */
    RESOURCE("resource", "资源生命周期");

    /** 角色与模板标识（bug-hunter/&lt;id&gt;）。 */
    public final String id;

    /** 视角名。 */
    public final String label;

    Perspective(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  /** 预算（03§6 BugHunter 行）。 */
  // v1.3.2：输出 2k→4k——M3 实测 dataflow 单次输出 4379 token 击穿 2k（budget_exceeded）；
  // 每条 finding ≈600-900 token，4k 容纳 4-5 条
  private static final AgentBudget BUDGET = new AgentBudget(24_000, 4_000, 3, 240_000);

  private final Perspective perspective;
  private final TemplateSupport templates;
  private final AgentRole role;
  private final String templatePath;

  /**
   * @param perspective 视角
   * @param templates 模板装载器（组合根经 {@code AgentsAssembly} 注入）
   */
  public BugHunterDefinition(Perspective perspective, TemplateSupport templates) {
    this.perspective = Objects.requireNonNull(perspective, "perspective");
    this.templates = Objects.requireNonNull(templates, "templates");
    this.role = new AgentRole("bug-hunter/" + perspective.id, "BugHunter·" + perspective.label);
    this.templatePath = "prompts/reviewer/bug-hunter/" + perspective.id + ".md";
  }

  /**
   * 视角。
   *
   * @return 视角
   */
  public Perspective perspective() {
    return perspective;
  }

  @Override
  public AgentRole role() {
    return role;
  }

  @Override
  public AgentBrief buildBrief(ReviewAgentInput input) {
    String rendered =
        templates.render(
            templatePath,
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
                "review_policy_section",
                input.reviewPolicySection().isBlank()
                    ? "（本仓库未配置 REVIEW.md 评审策略）"
                    : input.reviewPolicySection(),
                "scoped_paths_note",
                scopedPathsNote(input),
                "layer_contract_section",
                input.layerContractSection()));
    return TemplateSupport.briefOf(
        templates, templatePath, rendered, role, tools(), budget(), modelTier());
  }

  @Override
  public ToolWhitelist tools() {
    // 03§2.3：白名单默认 read_file(scoped)；作用域由编排器在派发时绑定（ReviewAgentInput.scopedPaths）
    return ToolWhitelist.READ_FILE;
  }

  @Override
  public AgentBudget budget() {
    return BUDGET;
  }

  private static String scopedPathsNote(ReviewAgentInput input) {
    return TemplateSupport.scopedPathsNote(input.scopedPaths(), ToolWhitelist.READ_FILE_NAME);
  }

  @Override
  public String toString() {
    return role.id()
        + "@"
        + Integer.toHexString(System.identityHashCode(this)).toUpperCase(Locale.ROOT);
  }
}
