package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T2.2/T2.3 班组装配测试：四视角齐备且派发序固定（dataflow → boundary → concurrency → resource）+ 四专角色装配。 */
class ReviewCrewTest {

  @Test
  void crewShipsAllFourPerspectivesInPriorityOrder() {
    ReviewCrew crew = AgentsAssembly.createReviewCrew();
    assertThat(crew.bugHunters()).hasSize(4);
    assertThat(crew.bugHunters())
        .extracting(agent -> agent.role().id())
        .containsExactly(
            "bug-hunter/dataflow",
            "bug-hunter/boundary",
            "bug-hunter/concurrency",
            "bug-hunter/resource");
    // 同一 TemplateLoader 实例（模板缓存共享，线程安全由 TemplateLoader 保证）
    assertThat(crew.bugHunters())
        .allSatisfy(agent -> assertThat(agent).isInstanceOf(BugHunterDefinition.class));
  }

  @Test
  void emptyCrewIsTolerated() {
    ReviewCrew crew = ReviewCrew.ofBugHunters(java.util.List.of());
    assertThat(crew.bugHunters()).isEmpty();
    assertThat(crew.securityReviewer()).isNull();
    ReviewCrew nullTolerated = new ReviewCrew(null, null, null, null, null);
    assertThat(nullTolerated.bugHunters()).isEmpty();
  }

  @Test
  void crewShipsAllSpecialists() {
    // T2.3：Security/Test/Convention/Perf 四专角色装配齐备，预算/工具/档位对齐 03§6
    ReviewCrew crew = AgentsAssembly.createReviewCrew();
    assertThat(crew.securityReviewer().role().id()).isEqualTo("security-reviewer");
    assertThat(crew.securityReviewer().modelTier()).isEqualTo(AgentTier.STRONG);
    assertThat(crew.securityReviewer().tools()).isEqualTo(ToolWhitelist.READ_FILE_AND_GREP);
    assertThat(crew.securityReviewer().budget())
        .isEqualTo(new AgentBudget(24_000, 2_000, 3, 240_000));
    assertThat(crew.testReviewer().role().id()).isEqualTo("test-reviewer");
    assertThat(crew.testReviewer().budget()).isEqualTo(new AgentBudget(12_000, 2_000, 2, 120_000));
    assertThat(crew.conventionReviewer().role().id()).isEqualTo("convention-reviewer");
    assertThat(crew.conventionReviewer().modelTier()).isEqualTo(AgentTier.LIGHT);
    assertThat(crew.conventionReviewer().tools()).isEqualTo(ToolWhitelist.NONE);
    assertThat(crew.conventionReviewer().budget())
        .isEqualTo(new AgentBudget(8_000, 1_000, 1, 60_000));
    assertThat(crew.perfReviewer().role().id()).isEqualTo("perf-reviewer");
    assertThat(crew.perfReviewer().budget()).isEqualTo(new AgentBudget(16_000, 1_000, 2, 120_000));
    // M4f-lite：CrossChecker 装配齐备（触发由编排器按 agents.crosschecker.enabled + 档位判定，05§5 默认 false）
    assertThat(crew.crossChecker().role().id()).isEqualTo("cross-checker");
    assertThat(crew.crossChecker().budget()).isEqualTo(new AgentBudget(16_000, 1_500, 2, 240_000));
    assertThat(crew.crossChecker().tools()).isEqualTo(ToolWhitelist.READ_FILE);
  }
}
