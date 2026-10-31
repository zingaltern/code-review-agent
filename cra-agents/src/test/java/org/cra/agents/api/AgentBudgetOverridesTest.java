package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/** 单代理预算覆盖（05§5 agents.budget.&lt;role&gt;.*；03§6 默认值 + AI Coding 放宽档，ADR-0015 §2.2 修订）。 */
class AgentBudgetOverridesTest {

  @Test
  void defaultsMatchSpecRows() {
    // 03§6 速查表（AI Coding 放宽档）：三个角色上调，其余保持现值
    assertThat(AgentsAssembly.createVerifier().budget())
        .isEqualTo(new AgentBudget(32_000, 4_000, 2, 240_000));
    assertThat(AgentsAssembly.createJudge().budget())
        .isEqualTo(new AgentBudget(40_000, 3_000, 2, 240_000));
    ReviewCrew crew = AgentsAssembly.createReviewCrew();
    assertThat(crew.testReviewer().budget()).isEqualTo(new AgentBudget(32_000, 8_000, 2, 120_000));
    assertThat(crew.bugHunters().get(0).budget())
        .isEqualTo(new AgentBudget(48_000, 8_000, 3, 240_000));
    assertThat(crew.securityReviewer().budget())
        .isEqualTo(new AgentBudget(48_000, 4_000, 3, 240_000));
    assertThat(crew.crossChecker().budget()).isEqualTo(new AgentBudget(32_000, 8_000, 2, 240_000));
    assertThat(crew.conventionReviewer().budget())
        .isEqualTo(new AgentBudget(16_000, 2_000, 1, 60_000));
    assertThat(crew.perfReviewer().budget()).isEqualTo(new AgentBudget(32_000, 2_000, 2, 120_000));
  }

  @Test
  void overridesSingleDimensionAndKeepsOtherDefaults() {
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(Map.of("agents.budget.verifier.input-tokens", "18000"));

    SubAgent<VerificationBatchInput> verifier = overrides.apply(AgentsAssembly.createVerifier());

    assertThat(verifier.budget())
        .isEqualTo(new AgentBudget(18_000, 4_000, 2, 240_000)); // 输出/轮次/超时沿用默认
    assertThat(verifier.role().id()).isEqualTo("verifier");
    assertThat(verifier.tools()).isEqualTo(ToolWhitelist.NONE);
  }

  @Test
  void bugHunterKeyCoversAllPerspectives() {
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.of(
            Map.of("bug-hunter", new AgentBudgetOverrides.RoleOverride(28_000L, null)));

    ReviewCrew crew = overrides.apply(AgentsAssembly.createReviewCrew());

    assertThat(crew.bugHunters()).hasSize(4);
    assertThat(crew.bugHunters())
        .allSatisfy(
            hunter -> {
              assertThat(hunter.role().id()).startsWith("bug-hunter/");
              assertThat(hunter.budget()).isEqualTo(new AgentBudget(28_000, 8_000, 3, 240_000));
            });
    // 其他角色不受该键影响
    assertThat(crew.testReviewer().budget().maxInputTokens()).isEqualTo(32_000);
  }

  @Test
  void fromConfigNormalizesRoleKeyCaseAndWhitespace() {
    // 整键首尾空白 + 角色段大小写/空白归一；用非默认值确保“覆盖生效”与“被丢弃”可区分
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(
            Map.of(
                " agents.budget.VERIFIER.input-tokens ", " 18000 ",
                "agents.budget. Judge .output-tokens", "2000"));

    assertThat(overrides.isEmpty()).isFalse();
    assertThat(overrides.byRole()).containsOnlyKeys("verifier", "judge");

    SubAgent<VerificationBatchInput> verifier = overrides.apply(AgentsAssembly.createVerifier());
    assertThat(verifier.budget()).isEqualTo(new AgentBudget(18_000, 4_000, 2, 240_000));

    SubAgent<JudgeCaseInput> judge = overrides.apply(AgentsAssembly.createJudge());
    assertThat(judge.budget()).isEqualTo(new AgentBudget(40_000, 2_000, 2, 240_000));
  }

  @Test
  void overrideReachesBriefBudgetConsumedByExecution() {
    // AgentRunner 读 brief.budget()；装饰器必须把覆盖同步进 brief，否则输出覆盖完全无效
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(
            Map.of(
                "agents.budget.verifier.input-tokens", "18000",
                "agents.budget.verifier.output-tokens", "2500"));
    SubAgent<VerificationBatchInput> verifier = overrides.apply(AgentsAssembly.createVerifier());

    AgentBrief brief =
        verifier.buildBrief(
            new VerificationBatchInput(
                "src/Foo.java",
                List.of(new VerificationItem("f_01", "bug", "important", "t", "d", "f", "e", null)),
                "code",
                "",
                "",
                ""));

    assertThat(brief.budget()).isEqualTo(new AgentBudget(18_000, 2_500, 2, 240_000));
    assertThat(brief.role().id()).isEqualTo("verifier");
  }

  @Test
  void applyRejectsOutputOverrideNotSmallerThanDefaultInput() {
    // 单维度覆盖只有与定义默认值合并后才能判定：输出 32000 与 verifier 默认输入 32000 相等即违规
    // （2026-10-31 翻倍后；RoleOverride 构造期上限同为 32000）
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(Map.of("agents.budget.verifier.output-tokens", "32000"));

    assertThatThrownBy(() -> overrides.apply(AgentsAssembly.createVerifier()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于")
        .hasMessageContaining("含定义默认值");
  }

  @Test
  void judgeOverrideAndNoneAreComposable() {
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(
            Map.of(
                "agents.budget.judge.input-tokens", "24000",
                "agents.budget.judge.output-tokens", "2000"));

    SubAgent<JudgeCaseInput> judge = overrides.apply(AgentsAssembly.createJudge());

    assertThat(judge.budget()).isEqualTo(new AgentBudget(24_000, 2_000, 2, 240_000));
    assertThat(overrides.isEmpty()).isFalse();
    assertThat(AgentBudgetOverrides.none().isEmpty()).isTrue();
    assertThat(AgentBudgetOverrides.none().apply(judge)).isSameAs(judge);
  }

  @Test
  void blankValuesAreTreatedAsUnset() {
    Map<String, String> keys = new LinkedHashMap<>();
    keys.put("agents.budget.verifier.input-tokens", "");
    keys.put("agents.budget.verifier.output-tokens", "  ");
    keys.put("agents.budget.unknown-role.input-tokens", "12000");
    keys.put("other.key", "1");

    assertThat(AgentBudgetOverrides.fromConfig(keys).isEmpty()).isTrue();
  }

  @Test
  void rejectsUnknownRoleAndIllegalValues() {
    assertThatThrownBy(
            () ->
                AgentBudgetOverrides.of(
                    Map.of("nope", new AgentBudgetOverrides.RoleOverride(16_000L, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知角色键");
    assertThatThrownBy(
            () ->
                AgentBudgetOverrides.fromConfig(Map.of("agents.budget.verifier.input-tokens", "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须为整数");
    assertThatThrownBy(
            () ->
                AgentBudgetOverrides.of(
                    Map.of("verifier", new AgentBudgetOverrides.RoleOverride(500L, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("input-tokens");
    assertThatThrownBy(
            () ->
                AgentBudgetOverrides.of(
                    Map.of("verifier", new AgentBudgetOverrides.RoleOverride(2_000L, 3_000L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于");
    assertThatThrownBy(
            () ->
                AgentBudgetOverrides.of(
                    Map.of("verifier", new AgentBudgetOverrides.RoleOverride(16_000L, 20_000L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于");
  }

  @Test
  void overrideWinsOverDefinitionDefaultAndKeepsTemplateVersioning() {
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(
            Map.of(
                "agents.budget.test-reviewer.input-tokens", "20000",
                "agents.budget.test-reviewer.output-tokens", "5000"));

    ReviewCrew crew = overrides.apply(AgentsAssembly.createReviewCrew());
    SubAgent<ReviewAgentInput> testReviewer = crew.testReviewer();

    assertThat(testReviewer.budget()).isEqualTo(new AgentBudget(20_000, 5_000, 2, 120_000));
    // 装饰器只换预算：模板/角色/工具与原型一致
    BugHunterDefinition dataflow =
        new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader());
    SubAgent<ReviewAgentInput> decorated =
        AgentBudgetOverrides.fromConfig(Map.of("agents.budget.bug-hunter.input-tokens", "30000"))
            .apply(dataflow);
    assertThat(decorated.role()).isEqualTo(dataflow.role());
    assertThat(decorated.modelTier()).isEqualTo(dataflow.modelTier());
    assertThat(decorated.tools()).isEqualTo(dataflow.tools());
    assertThat(decorated.budget()).isEqualTo(new AgentBudget(30_000, 8_000, 3, 240_000));

    ReviewAgentInput input =
        new ReviewAgentInput("t", "m", "@@ -1 +1 @@", "", "", "", "", Set.of());
    assertThat(decorated.buildBrief(input).templateVersion())
        .isEqualTo(dataflow.buildBrief(input).templateVersion());
  }
}
