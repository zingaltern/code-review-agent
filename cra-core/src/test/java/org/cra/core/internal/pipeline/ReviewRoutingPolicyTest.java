package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.run.Depth;
import org.junit.jupiter.api.Test;

/** 评审路由与专角色触发判定单测（02§4.3/02§4.4/03§3），与扇出执行解耦。 */
class ReviewRoutingPolicyTest {

  @Test
  void routePerspectivesFollowsDepthTable() {
    // 02§4.3：quick 1 / standard 2 / deep 3–4
    assertThat(ReviewRoutingPolicy.routePerspectives(Depth.QUICK))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
    assertThat(ReviewRoutingPolicy.routePerspectives(Depth.STANDARD))
        .containsExactly(
            BugHunterDefinition.Perspective.DATAFLOW, BugHunterDefinition.Perspective.BOUNDARY);
    assertThat(ReviewRoutingPolicy.routePerspectives(Depth.DEEP))
        .containsExactly(
            BugHunterDefinition.Perspective.DATAFLOW,
            BugHunterDefinition.Perspective.BOUNDARY,
            BugHunterDefinition.Perspective.CONCURRENCY,
            BugHunterDefinition.Perspective.RESOURCE);
  }

  @Test
  void degradePerspectivesDropsOneKeepsOne() {
    assertThat(
            ReviewRoutingPolicy.degradePerspectives(
                ReviewRoutingPolicy.routePerspectives(Depth.DEEP)))
        .hasSize(3);
    assertThat(
            ReviewRoutingPolicy.degradePerspectives(
                ReviewRoutingPolicy.routePerspectives(Depth.STANDARD)))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
    assertThat(
            ReviewRoutingPolicy.degradePerspectives(
                ReviewRoutingPolicy.routePerspectives(Depth.QUICK)))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
  }

  @Test
  void testReviewerTriggeredOnlyWhenTestRatioLow() {
    // 03§3：逻辑文件变更且测试变更比例低；≥0.5 不触发
    DiffResult logicOnly =
        new DiffResult(
            "b",
            "h",
            List.of(new DiffFile("src/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    DiffResult balanced =
        new DiffResult(
            "b",
            "h",
            List.of(
                new DiffFile("src/A.java", null, "modified", "@@ -1 +1 @@", List.of()),
                new DiffFile("src/test/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    assertThat(ReviewRoutingPolicy.testReviewerTriggered(logicOnly)).isTrue();
    assertThat(ReviewRoutingPolicy.testReviewerTriggered(balanced)).isFalse();
  }

  @Test
  void conventionTriggeredOnlyWithConventionsConfigured() {
    assertThat(ReviewRoutingPolicy.conventionTriggered(ReviewPolicy.empty())).isFalse();
    ReviewPolicy withConventions =
        new ReviewPolicy(
            true,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("禁止使用 Fastjson"),
            List.of(),
            List.of(),
            List.of());
    assertThat(ReviewRoutingPolicy.conventionTriggered(withConventions)).isTrue();
  }

  @Test
  void perfTriggeredOnlyInDeep() {
    assertThat(
            ReviewRoutingPolicy.perfTriggered(
                new DepthPlan(Depth.STANDARD, 30, true, 200, false, List.of())))
        .isFalse();
    assertThat(
            ReviewRoutingPolicy.perfTriggered(
                new DepthPlan(Depth.DEEP, 60, true, 400, false, List.of())))
        .isTrue();
  }
}
