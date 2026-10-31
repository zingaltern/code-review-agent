package org.cra.agents.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单代理预算覆盖（05§5 {@code agents.budget.<role>.*} 的运行时形态；03§6 速查表是默认值，本类只做覆盖）。
 *
 * <p>配置键与角色 id 对齐（kebab-case，05§1.1）：{@code bug-hunter}（四个视角共用一个键）、{@code security-reviewer}、{@code
 * test-reviewer}、{@code convention-reviewer}、{@code perf-reviewer}、{@code cross-checker}、{@code
 * verifier}、{@code judge}。每角色两个维度：{@code input-tokens} / {@code
 * output-tokens}；未覆盖的维度沿用定义自带默认（03§6）。
 *
 * <p>覆盖在组合根以装饰器形式施加（{@link #apply})：核心只面向 {@link SubAgent} 接口，预算读取点（上下文裁剪、AgentRunner
 * 硬限、验证/仲裁组装）自然生效，无需改动编排逻辑。轮次与超时不在覆盖范围（03§6 的隔离机制不随配置放宽）。
 *
 * <p>校验时序：两维度都覆盖时在构造期校验 input&gt;output；<strong>单维度覆盖</strong>必须与定义默认值合并后才能判定（{@link #apply} 期的
 * merge 校验），此时才可能抛 IllegalArgumentException——配置错误仍在组装期（退出码 2）暴露，不会带病运行。
 */
public final class AgentBudgetOverrides {

  /** 可覆盖角色（顺序即文档顺序；键 = 角色 id 前缀，05§5）。 */
  public static final List<String> CONFIGURABLE_ROLES =
      List.of(
          "bug-hunter",
          "security-reviewer",
          "test-reviewer",
          "convention-reviewer",
          "perf-reviewer",
          "cross-checker",
          "verifier",
          "judge");

  private static final Set<String> ROLE_KEYS = Set.copyOf(CONFIGURABLE_ROLES);

  /** 输入预算下限（更小无法容纳 brief 定界与结论全文）。 */
  private static final long MIN_INPUT_TOKENS = 1_000;

  /** 输入预算上限（防止单代理预算挤爆 run 级预算）。 */
  private static final long MAX_INPUT_TOKENS = 200_000;

  /** 输出预算下限。 */
  private static final long MIN_OUTPUT_TOKENS = 200;

  /** 输出预算上限。 */
  private static final long MAX_OUTPUT_TOKENS = 32_000;

  /**
   * 单角色覆盖。
   *
   * @param inputTokens 输入预算覆盖（null = 沿用定义默认，03§6）
   * @param outputTokens 输出预算覆盖（null = 沿用定义默认）
   */
  public record RoleOverride(Long inputTokens, Long outputTokens) {

    public RoleOverride {
      if (inputTokens != null
          && (inputTokens < MIN_INPUT_TOKENS || inputTokens > MAX_INPUT_TOKENS)) {
        throw new IllegalArgumentException(
            "input-tokens 必须在 [%d,%d]: %d"
                .formatted(MIN_INPUT_TOKENS, MAX_INPUT_TOKENS, inputTokens));
      }
      if (outputTokens != null
          && (outputTokens < MIN_OUTPUT_TOKENS || outputTokens > MAX_OUTPUT_TOKENS)) {
        throw new IllegalArgumentException(
            "output-tokens 必须在 [%d,%d]: %d"
                .formatted(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS, outputTokens));
      }
      if (inputTokens != null && outputTokens != null && inputTokens <= outputTokens) {
        throw new IllegalArgumentException(
            "input-tokens 必须大于 output-tokens: %d <= %d".formatted(inputTokens, outputTokens));
      }
    }
  }

  private static final AgentBudgetOverrides NONE = new AgentBudgetOverrides(Map.of());

  private final Map<String, RoleOverride> byRole;

  private AgentBudgetOverrides(Map<String, RoleOverride> byRole) {
    this.byRole = Map.copyOf(byRole);
  }

  /**
   * 无覆盖（默认；各定义自带默认值生效）。
   *
   * @return 空覆盖
   */
  public static AgentBudgetOverrides none() {
    return NONE;
  }

  /**
   * 显式构造（角色键非法即拒绝——配置错误在组装期暴露）。
   *
   * @param byRole 角色键 → 覆盖
   * @return 覆盖
   */
  public static AgentBudgetOverrides of(Map<String, RoleOverride> byRole) {
    if (byRole == null || byRole.isEmpty()) {
      return NONE;
    }
    Map<String, RoleOverride> copy = new LinkedHashMap<>();
    byRole.forEach(
        (role, override) -> {
          String key = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
          if (!ROLE_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                "未知角色键: " + role + "（可配置: " + CONFIGURABLE_ROLES + "）");
          }
          if (override != null
              && (override.inputTokens() != null || override.outputTokens() != null)) {
            copy.put(key, override);
          }
        });
    return copy.isEmpty() ? NONE : new AgentBudgetOverrides(copy);
  }

  /**
   * 从配置键解析（{@code agents.budget.<role>.input-tokens} / {@code agents.budget.<role>.output-tokens}）。
   *
   * <p>空白值等同未配置（GitHub Actions 的空 input 是 {@code ""}，不是 null）。
   *
   * @param values 配置键 → 原值（可含其他键，未命中的忽略）
   * @return 覆盖
   */
  public static AgentBudgetOverrides fromConfig(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return NONE;
    }
    Map<String, RoleOverride> collected = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      // 整键先归一（首尾空白）：此前仅在 role/field 段 strip，键首空白会让 startsWith 失配而静默丢弃配置
      String key = entry.getKey() == null ? null : entry.getKey().strip();
      if (key == null || !key.startsWith("agents.budget.")) {
        continue;
      }
      String rest = key.substring("agents.budget.".length());
      int dot = rest.lastIndexOf('.');
      if (dot <= 0) {
        continue;
      }
      // 角色键大小写/首尾空白不敏感（整键先 strip，角色段再归一）——静默丢弃配置比拒绝更危险
      String role = rest.substring(0, dot).strip().toLowerCase(Locale.ROOT);
      String field = rest.substring(dot + 1).strip();
      if (!ROLE_KEYS.contains(role)) {
        // 未知角色/字段按未配置忽略（fromConfig 接收整张配置表；严格校验走 of()）
        continue;
      }
      if (!"input-tokens".equals(field) && !"output-tokens".equals(field)) {
        continue;
      }
      String raw = entry.getValue();
      if (raw == null || raw.isBlank()) {
        continue;
      }
      long value;
      try {
        value = Long.parseLong(raw.strip());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "agents.budget." + role + "." + field + " 必须为整数: " + raw, e);
      }
      RoleOverride existing = collected.get(role);
      Long input = existing == null ? null : existing.inputTokens();
      Long output = existing == null ? null : existing.outputTokens();
      if ("input-tokens".equals(field)) {
        input = Long.valueOf(value);
      } else {
        output = Long.valueOf(value);
      }
      collected.put(role, new RoleOverride(input, output));
    }
    return of(collected);
  }

  /**
   * 覆盖是否为空。
   *
   * @return true 无任何覆盖
   */
  public boolean isEmpty() {
    return byRole.isEmpty();
  }

  /**
   * 角色键 → 覆盖（不可变副本）。
   *
   * @return 覆盖表
   */
  public Map<String, RoleOverride> byRole() {
    return byRole;
  }

  /**
   * 对单个子代理施加覆盖（未命中或覆盖维度为空时原样返回）。
   *
   * @param agent 子代理定义
   * @param <I> 输入类型
   * @return 带覆盖预算的子代理
   */
  public <I> SubAgent<I> apply(SubAgent<I> agent) {
    if (agent == null || byRole.isEmpty()) {
      return agent;
    }
    RoleOverride override = match(agent.role().id());
    if (override == null) {
      return agent;
    }
    return new BudgetedAgent<>(agent, merge(agent.budget(), override));
  }

  /**
   * 对整班组施加覆盖（null 成员原样保留——未装配语义不变）。
   *
   * @param crew 班组
   * @return 覆盖后的班组
   */
  public ReviewCrew apply(ReviewCrew crew) {
    if (crew == null || byRole.isEmpty()) {
      return crew;
    }
    List<SubAgent<ReviewAgentInput>> hunters = new ArrayList<>();
    for (SubAgent<ReviewAgentInput> hunter : crew.bugHunters()) {
      hunters.add(apply(hunter));
    }
    return new ReviewCrew(
        hunters,
        apply(crew.securityReviewer()),
        apply(crew.testReviewer()),
        apply(crew.conventionReviewer()),
        apply(crew.perfReviewer()),
        apply(crew.crossChecker()));
  }

  private RoleOverride match(String roleId) {
    if (roleId == null) {
      return null;
    }
    RoleOverride direct = byRole.get(roleId);
    if (direct != null) {
      return direct;
    }
    // 视角族（bug-hunter/dataflow 等）共用一个键
    int slash = roleId.indexOf('/');
    return slash > 0 ? byRole.get(roleId.substring(0, slash)) : null;
  }

  private static AgentBudget merge(AgentBudget base, RoleOverride override) {
    Objects.requireNonNull(base, "base");
    long input = override.inputTokens() == null ? base.maxInputTokens() : override.inputTokens();
    long output =
        override.outputTokens() == null ? base.maxOutputTokens() : override.outputTokens();
    if (input <= output) {
      throw new IllegalArgumentException(
          "input-tokens 必须大于 output-tokens: %d <= %d（含定义默认值）".formatted(input, output));
    }
    return new AgentBudget(input, output, base.maxTurns(), base.timeoutMs());
  }

  /** 只替换 budget() 的装饰器（纯定义，无状态；brief 同步重建，确保执行侧读到的预算与覆盖一致）。 */
  private static final class BudgetedAgent<I> implements SubAgent<I> {

    private final SubAgent<I> delegate;
    private final AgentBudget budget;

    private BudgetedAgent(SubAgent<I> delegate, AgentBudget budget) {
      this.delegate = delegate;
      this.budget = budget;
    }

    @Override
    public AgentRole role() {
      return delegate.role();
    }

    @Override
    public AgentBrief buildBrief(I input) {
      AgentBrief brief = delegate.buildBrief(input);
      return new AgentBrief(
          brief.role(),
          brief.systemPrompt(),
          brief.userPrompt(),
          brief.templateVersion(),
          brief.tools(),
          budget,
          brief.modelTier());
    }

    @Override
    public ToolWhitelist tools() {
      return delegate.tools();
    }

    @Override
    public AgentBudget budget() {
      return budget;
    }

    @Override
    public AgentTier modelTier() {
      return delegate.modelTier();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
