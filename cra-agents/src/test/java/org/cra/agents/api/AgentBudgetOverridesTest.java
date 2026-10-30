package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/** 单代理预算覆盖（05§5 agents.budget.&lt;role&gt;.*；03§6 默认值 + AI Coding 放宽档，ADR-0015 §2.2 修订）。 */
class AgentBudgetOverridesTest {

  @Test
  void defaultsMatchSpecRows() {
    // 03§6 速查表（AI Coding 放宽档）：三个角色上调，其余保持现值
    assertThat(AgentsAssembly.createVerifier().budget())
        .isEqualTo(new AgentBudget(16_000, 2_000, 2, 240_000));
    assertThat(AgentsAssembly.createJudge().budget())
        .isEqualTo(new AgentBudget(20_000, 1_500, 2, 240_000));
    ReviewCrew crew = AgentsAssembly.createReviewCrew();
    assertThat(crew.testReviewer().budget()).isEqualTo(new AgentBudget(16_000, 4_000, 2, 120_000));
    assertThat(crew.bugHunters().get(0).budget())
        .isEqualTo(new AgentBudget(24_000, 4_000, 3, 240_000));
    assertThat(crew.securityReviewer().budget())
        .isEqualTo(new AgentBudget(24_000, 2_000, 3, 240_000));
    assertThat(crew.crossChecker().budget()).isEqualTo(new AgentBudget(16_000, 4_000, 2, 240_000));
    assertThat(crew.conventionReviewer().budget())
        .isEqualTo(new AgentBudget(8_000, 1_000, 1, 60_000));
    assertThat(crew.perfReviewer().budget()).isEqualTo(new AgentBudget(16_000, 1_000, 2, 120_000));
  }

  @Test
  void overridesSingleDimensionAndKeepsOtherDefaults() {
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(Map.of("agents.budget.verifier.input-tokens", "18000"));

    SubAgent<VerificationBatchInput> verifier = overrides.apply(AgentsAssembly.createVerifier());

    assertThat(verifier.budget())
        .isEqualTo(new AgentBudget(18_000, 2_000, 2, 240_000)); // 输出/轮次/超时沿用默认
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
              assertThat(hunter.budget()).isEqualTo(new AgentBudget(28_000, 4_000, 3, 240_000));
            });
    // 其他角色不受该键影响
    assertThat(crew.testReviewer().budget().maxInputTokens()).isEqualTo(16_000);
  }

  @Test
  void fromConfigNormalizesRoleKeyCaseAndWhitespace() {
    // PR #64 评审跟进：fromConfig 与 of() 同一归一化口径——大小写/首尾空白不静默丢弃
    AgentBudgetOverrides overrides =
        AgentBudgetOverrides.fromConfig(
            Map.of(
                " agents.budget.VERIFIER.input-tokens ", " 16000 ",
                "agents.budget. Judge .output-tokens", "1500"));

    SubAgent<VerificationBatchInput> verifier = overrides.apply(AgentsAssembly.createVerifier());
    assertThat(verifier.budget()).isEqualTo(new AgentBudget(16_000, 2_000, 2, 240_000));

    SubAgent<JudgeCaseInput> judge = overrides.apply(AgentsAssembly.createJudge());
    assertThat(judge.budget()).isEqualTo(new AgentBudget(20_000, 1_500, 2, 240_000));
  }

  @Test
  void crewAndSingleAgentOverridesAreComposable() {
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
  }
}
