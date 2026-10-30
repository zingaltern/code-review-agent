package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.run.Depth;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.PreExistingBlameChecker.Verdict;
import org.junit.jupiter.api.Test;

/** M1 编排纯函数单元测试：深度档位（02§4.3/04§2.2）、分 chunk（04§2.2）、降档阶梯（02§4.4）、聚合规则（T1.7）。 */
class M1PipelinePoliciesTest {

  private static final PipelineOptions OPTIONS = PipelineOptions.defaults();

  // ---------- DepthPolicy ----------

  @Test
  void standardSmallDiffAutoQuick() {
    DepthPlan plan = DepthPolicy.resolve(Depth.STANDARD, 299, OPTIONS);
    assertThat(plan.effectiveDepth()).isEqualTo(Depth.QUICK);
    assertThat(plan.conditionalFullText()).isFalse();
    assertThat(plan.notes()).anyMatch(n -> n.contains("自动 quick"));
  }

  @Test
  void standardDefaultIsWindow30WithFullText() {
    DepthPlan plan = DepthPolicy.resolve(Depth.STANDARD, 300, OPTIONS);
    assertThat(plan.effectiveDepth()).isEqualTo(Depth.STANDARD);
    assertThat(plan.windowLines()).isEqualTo(30);
    assertThat(plan.conditionalFullText()).isTrue();
    assertThat(plan.priorityOnly()).isFalse();
  }

  @Test
  void deepWidensWindowAndFullTextBudget() {
    DepthPlan plan = DepthPolicy.resolve(Depth.DEEP, 500, OPTIONS);
    assertThat(plan.windowLines()).isEqualTo(60);
    assertThat(plan.fullFileMaxLines()).isEqualTo(400);
  }

  @Test
  void largeDiffDowngradesQualityRegardlessOfDepth() {
    DepthPlan plan = DepthPolicy.resolve(Depth.DEEP, 4001, OPTIONS);
    assertThat(plan.windowLines()).isEqualTo(15);
    assertThat(plan.conditionalFullText()).isFalse();
    assertThat(plan.notes()).anyMatch(n -> n.contains("降为 Quick 质量"));
  }

  @Test
  void hardCapEnablesPriorityOnly() {
    DepthPlan plan = DepthPolicy.resolve(Depth.STANDARD, 16001, OPTIONS);
    assertThat(plan.priorityOnly()).isTrue();
  }

  // ---------- ChunkPlanner ----------

  private static DiffFile file(String path) {
    return new DiffFile(
        path, null, "modified", "@@ -1 +1,2 @@\n+x\n", List.of(new DiffHunk(1, 1, 1, 2)));
  }

  @Test
  void groupsByModuleAndCapsThreeFilesPerChunk() {
    List<ChunkPlanner.Chunk> chunks =
        ChunkPlanner.plan(
                List.of(
                    file("src/main/A.java"),
                    file("src/main/B.java"),
                    file("src/main/C.java"),
                    file("src/main/D.java"),
                    file("app/E.java")),
                ReviewPolicy.empty(),
                false,
                false)
            .chunks();
    // src/main 4 文件 → 3+1；app 1 文件 → 1
    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).files()).hasSize(3);
    assertThat(chunks.get(1).files()).hasSize(1);
    assertThat(chunks.get(2).key()).isEqualTo("app");
  }

  @Test
  void skipPathsRemovedFromScopeAndDeclared() {
    ChunkPlanner.Plan plan =
        ChunkPlanner.plan(
            List.of(file("src/A.java"), file("generated/G.java")),
            new ReviewPolicy(
                false,
                List.of(),
                null,
                List.of(),
                List.of("generated/**"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            false,
            false);
    assertThat(plan.chunks()).hasSize(1);
    assertThat(plan.chunks().get(0).files().get(0).path()).isEqualTo("src/A.java");
    assertThat(plan.skippedPaths()).containsExactly("generated/G.java");
  }

  @Test
  void priorityOnlyFiltersAndFallsBackWhenUnconfigured() {
    ChunkPlanner.Plan filtered =
        ChunkPlanner.plan(
            List.of(file("billing/C.java"), file("misc/O.java")),
            new ReviewPolicy(
                false,
                List.of(),
                null,
                List.of("billing/**"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            true,
            false);
    assertThat(filtered.chunks().get(0).files().get(0).path()).isEqualTo("billing/C.java");
    assertThat(filtered.skippedPaths()).anyMatch(s -> s.startsWith("misc/O.java"));

    ChunkPlanner.Plan fallback =
        ChunkPlanner.plan(List.of(file("misc/O.java")), ReviewPolicy.empty(), true, false);
    assertThat(fallback.chunks()).hasSize(1);
    assertThat(fallback.notes()).anyMatch(n -> n.contains("未配置 priority_paths"));
  }

  @Test
  void pipelineOptionsCompatConstructorDefaultsToNoTestReview() {
    // 02§4.4：8 参兼容构造沿用默认策略（测试文件不进入评审范围）
    org.cra.core.api.PipelineOptions options =
        new org.cra.core.api.PipelineOptions(500_000, 0.70, 0.85, 2000, 8000, 3, "never", 20);
    assertThat(options.includeTestFiles()).isFalse();
    assertThat(options.runTotalTokens()).isEqualTo(500_000);
  }

  @Test
  void testFilesExcludedByDefaultAndCounted() {
    // 02§4.4：默认策略下测试文件不进评审范围，跳过数量必须可声明
    ChunkPlanner.Plan plan =
        ChunkPlanner.plan(
            List.of(
                file("src/main/java/demo/A.java"),
                file("src/test/java/demo/ATest.java"),
                file("src/main/java/demo/BTests.java")),
            ReviewPolicy.empty(),
            false,
            false);
    assertThat(plan.chunks()).hasSize(1);
    assertThat(plan.chunks().get(0).files())
        .extracting(DiffFile::path)
        .containsExactly("src/main/java/demo/A.java");
    assertThat(plan.skippedTestPaths())
        .containsExactly("src/test/java/demo/ATest.java", "src/main/java/demo/BTests.java");
    assertThat(plan.skippedTestCount()).isEqualTo(2);
    assertThat(plan.hasReviewableFiles()).isTrue();
  }

  @Test
  void testFilesReviewedWhenSwitchOn() {
    // --review-tests / CRA_REVIEW_TESTS=true：恢复测试文件评审
    ChunkPlanner.Plan plan =
        ChunkPlanner.plan(
            List.of(file("src/main/java/demo/A.java"), file("src/test/java/demo/ATest.java")),
            ReviewPolicy.empty(),
            false,
            true);
    assertThat(
            plan.chunks().stream()
                .flatMap(chunk -> chunk.files().stream())
                .map(DiffFile::path)
                .toList())
        .containsExactly("src/main/java/demo/A.java", "src/test/java/demo/ATest.java");
    assertThat(plan.skippedTestCount()).isZero();
    assertThat(plan.skippedTestPaths()).isEmpty();
  }

  @Test
  void pureTestChangeDeclaredAsNotReviewable() {
    // 02§4.4：纯测试变更 = 无可评审文件（完整声明与"不派发"由 ReviewStage/Summary 给出）
    ChunkPlanner.Plan plan =
        ChunkPlanner.plan(
            List.of(file("src/test/java/demo/ATest.java")), ReviewPolicy.empty(), false, false);
    assertThat(plan.chunks()).isEmpty();
    assertThat(plan.hasReviewableFiles()).isFalse();
    assertThat(plan.skippedTestCount()).isEqualTo(1);
    assertThat(plan.skippedTestPaths()).containsExactly("src/test/java/demo/ATest.java");
  }

  @Test
  void testEvidenceListIsBoundedForLargePullRequests() {
    // 02§4.4：测试清单必须口语有界——数百个测试文件不得吃穿 TestReviewer 的 12k 输入预算
    List<DiffFile> files = new ArrayList<>();
    for (int i = 0; i < 70; i++) {
      files.add(file("src/test/java/demo/Case" + i + "Test.java"));
    }
    ChunkPlanner.Plan plan = ChunkPlanner.plan(files, ReviewPolicy.empty(), false, false);
    String section = ReviewPromptSections.testExtraSection(plan);
    assertThat(section).contains("…等 10 个（共 70 个测试文件，超出清单上限 60");
    long listed = section.lines().filter(l -> l.startsWith("- ")).count();
    assertThat(listed).isEqualTo(60);
  }

  @Test
  void reviewMdSkipPathsStillStackWhenTestsIncluded() {
    // 04§3.1：REVIEW.md 的 Skip paths 与测试文件开关叠加生效（开关打开也不绕过 skip_paths）
    ReviewPolicy policy =
        new ReviewPolicy(
            false,
            List.of(),
            null,
            List.of(),
            List.of("generated/**"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    ChunkPlanner.Plan plan =
        ChunkPlanner.plan(
            List.of(file("generated/GTest.java"), file("src/test/java/demo/ATest.java")),
            policy,
            false,
            true);
    assertThat(plan.skippedPaths()).containsExactly("generated/GTest.java");
    assertThat(plan.skippedTestCount()).isZero();
    assertThat(plan.chunks().get(0).files())
        .extracting(DiffFile::path)
        .containsExactly("src/test/java/demo/ATest.java");
  }

  @Test
  void largeDiffAndHardCapBoundariesFollowRelaxedDefaults() {
    // AI Coding 放宽档（ADR-0015 §2.2 修订）：4000/16000 为边界值，严格大于才降档
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 4_000, OPTIONS).windowLines()).isEqualTo(30);
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 4_000, OPTIONS).conditionalFullText()).isTrue();
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 4_001, OPTIONS).windowLines()).isEqualTo(15);
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 4_001, OPTIONS).conditionalFullText()).isFalse();
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 16_000, OPTIONS).priorityOnly()).isFalse();
    assertThat(DepthPolicy.resolve(Depth.STANDARD, 16_001, OPTIONS).priorityOnly()).isTrue();
  }

  @Test
  void budgetDefaultsRaiseOnlyBudgetsNotLadderThresholds() {
    assertThat(OPTIONS.runTotalTokens()).isEqualTo(800_000L);
    assertThat(OPTIONS.largeDiffLines()).isEqualTo(4_000);
    assertThat(OPTIONS.hardCapLines()).isEqualTo(16_000);
    // 门禁/降档阈值不动：70% / 85%
    assertThat(OPTIONS.degradeAtFirst()).isEqualTo(0.70);
    assertThat(OPTIONS.degradeAtSecond()).isEqualTo(0.85);
  }

  // ---------- RunBudgetTracker ----------

  @Test
  void ladderPhasesFollowConsumedRatio() {
    // 阶梯阈值（70%/85%/100%）与 run 预算默认值解耦：用显式 500k 验证阶段语义本身
    RunBudgetTracker tracker =
        new RunBudgetTracker(
            new PipelineOptions(500_000, 0.70, 0.85, 4_000, 16_000, 3, "never", 20));
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.NORMAL);
    tracker.record(349_000, 0); // 69.8%
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.NORMAL);
    tracker.record(1_000, 0); // 70%
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.DEGRADE);
    tracker.record(74_000, 0); // 84.8%
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.DEGRADE);
    tracker.record(1_000, 0); // 85%
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.TRUNCATE);
    tracker.record(75_000, 0); // 100%
    assertThat(tracker.phase()).isEqualTo(RunBudgetTracker.Phase.STOP);
  }

  // ---------- FindingAggregator ----------

  private static CandidateFinding finding(String id, Severity severity, String file, int line) {
    return new CandidateFinding(
        id,
        "run_x",
        FindingCategory.BUG,
        "t",
        "d",
        "f",
        file,
        line,
        null,
        "e",
        null,
        severity,
        false,
        null,
        "general-reviewer",
        "glm-x");
  }

  private static FindingAggregator.Scored scored(CandidateFinding f, Verdict verdict) {
    return new FindingAggregator.Scored(f, "fp", AnchorStatus.INLINE, verdict);
  }

  @Test
  void ordersImportantBeforeNitThenPreExisting() {
    FindingAggregator.Aggregation aggregation =
        FindingAggregator.aggregate(
            List.of(
                scored(finding("f_n", Severity.NIT, "a.java", 1), Verdict.INTRODUCED_IN_PR),
                scored(finding("f_p", Severity.NIT, "a.java", 2), Verdict.CONFIRMED_PRE_EXISTING),
                scored(finding("f_i", Severity.IMPORTANT, "b.java", 1), Verdict.INTRODUCED_IN_PR)),
            ReviewPolicy.empty(),
            3,
            false);
    assertThat(aggregation.ordered())
        .extracting(s -> s.finding().id())
        .containsExactly("f_i", "f_n", "f_p");
  }

  @Test
  void nitBudgetZeroDropsAllNits() {
    FindingAggregator.Aggregation aggregation =
        FindingAggregator.aggregate(
            List.of(
                scored(finding("f_i", Severity.IMPORTANT, "a.java", 1), Verdict.INTRODUCED_IN_PR),
                scored(finding("f_n", Severity.NIT, "a.java", 2), Verdict.INTRODUCED_IN_PR)),
            new ReviewPolicy(
                false, List.of(), 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()),
            3,
            false);
    assertThat(aggregation.ordered()).extracting(s -> s.finding().id()).containsExactly("f_i");
    assertThat(aggregation.nitDropped()).isEqualTo(1);
  }

  @Test
  void truncationKeepsTop10AndCountsSuppressed() {
    List<FindingAggregator.Scored> candidates = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(
          scored(finding("f_" + i, Severity.IMPORTANT, "a.java", i + 1), Verdict.INTRODUCED_IN_PR));
    }
    FindingAggregator.Aggregation aggregation =
        FindingAggregator.aggregate(candidates, ReviewPolicy.empty(), 3, true);
    assertThat(aggregation.ordered()).hasSize(10);
    assertThat(aggregation.suppressedByTruncation()).isEqualTo(2);
  }
}
