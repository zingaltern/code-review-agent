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
  void testReviewerTriggerSeesTestFilesOutsideDefaultReviewScope() {
    // 02§4.4：测试文件默认不进评审计划，但触发比例必须按过滤前全量 diff 统计——
    // 改用过滤后计划（只剩 2 个生产文件）会让本用例误判为"测试比例 0 → 触发"
    DiffResult twoProdOneTest =
        new DiffResult(
            "b",
            "h",
            List.of(
                new DiffFile("src/main/A.java", null, "modified", "@@ -1 +1 @@", List.of()),
                new DiffFile("src/main/B.java", null, "modified", "@@ -1 +1 @@", List.of()),
                new DiffFile("src/main/ATest.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    assertThat(ReviewRoutingPolicy.testReviewerTriggered(twoProdOneTest)).isTrue();
    DiffResult oneProdOneTest =
        new DiffResult(
            "b",
            "h",
            List.of(
                new DiffFile("src/main/A.java", null, "modified", "@@ -1 +1 @@", List.of()),
                new DiffFile("src/main/BTest.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    assertThat(ReviewRoutingPolicy.testReviewerTriggered(oneProdOneTest)).isFalse();
  }

  @Test
  void isTestPathCoversDirectoryAndFilenamePatterns() {
    // 02§4.4：测试文件 = src/test/ 路径 或 *Test.java / *Tests.java
    assertThat(ReviewRoutingPolicy.isTestPath("src/test/java/demo/Foo.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("cra-core/src/test/resources/case.json")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/FooTest.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("test/com/demo/FooTests.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/Foo.java")).isFalse();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/Testing.java")).isFalse();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/TestSupport.java")).isFalse();
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

  @Test
  void testPathHeuristicExcludesExactTestClassName() {
    // 02§4.4：目录判定优先；后缀启发式要求前缀名非空——恰名为 Test.java/Tests.java 的生产文件不被误判
    assertThat(ReviewRoutingPolicy.isTestPath("src/test/java/demo/Foo.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("cra-core/src/test/java/demo/Foo.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/FooTest.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/FooTests.java")).isTrue();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/Test.java")).isFalse();
    assertThat(ReviewRoutingPolicy.isTestPath("src/main/java/demo/Tests.java")).isFalse();
  }
}
