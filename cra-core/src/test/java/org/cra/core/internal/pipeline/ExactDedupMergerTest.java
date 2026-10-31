package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.junit.jupiter.api.Test;

/** run 内精确去重 merge（03§4.5：同 file ±3 行同 category；语义去重留 M3）。 */
class ExactDedupMergerTest {

  private static int seq = 0;

  private static CandidateFinding finding(
      String file, FindingCategory category, int line, Severity severity, Double confidence) {
    seq++;
    return new CandidateFinding(
        "f_" + seq,
        "run_t",
        category,
        "t" + seq,
        "d",
        "f",
        file,
        line,
        Side.RIGHT,
        "e",
        null,
        severity,
        false,
        confidence,
        "bug-hunter/boundary",
        "m");
  }

  @Test
  void mergesSameFileSameCategoryWithinRadius() {
    CandidateFinding a = finding("F.java", FindingCategory.BUG, 10, Severity.IMPORTANT, 0.8);
    CandidateFinding b = finding("F.java", FindingCategory.BUG, 13, Severity.IMPORTANT, 0.9);
    ExactDedupMerger.Result result = ExactDedupMerger.merge(List.of(a, b));
    assertThat(result.kept()).hasSize(1);
    assertThat(result.mergedAway()).isEqualTo(1);
    // 代表元：confidence 高者
    assertThat(result.kept().get(0).id()).isEqualTo(b.id());
    assertThat(result.mergedFrom().get(b.id())).containsExactly(a.id());
  }

  @Test
  void keepsFindingsBeyondRadiusApart() {
    CandidateFinding a = finding("F.java", FindingCategory.BUG, 10, Severity.IMPORTANT, 0.8);
    CandidateFinding b = finding("F.java", FindingCategory.BUG, 14, Severity.IMPORTANT, 0.8);
    assertThat(ExactDedupMerger.merge(List.of(a, b)).kept()).hasSize(2);
  }

  @Test
  void differentCategoriesNeverMergeEvenOnSameLine() {
    CandidateFinding bug = finding("F.java", FindingCategory.BUG, 10, Severity.IMPORTANT, 0.8);
    CandidateFinding sec = finding("F.java", FindingCategory.SECURITY, 10, Severity.IMPORTANT, 0.8);
    assertThat(ExactDedupMerger.merge(List.of(bug, sec)).kept()).hasSize(2);
  }

  @Test
  void differentFilesNeverMerge() {
    CandidateFinding a = finding("A.java", FindingCategory.BUG, 10, Severity.IMPORTANT, 0.8);
    CandidateFinding b = finding("B.java", FindingCategory.BUG, 10, Severity.IMPORTANT, 0.8);
    assertThat(ExactDedupMerger.merge(List.of(a, b)).kept()).hasSize(2);
  }

  @Test
  void chainsTransitivelyWithinRadius() {
    // 10→12→14 链式合并为一簇（相邻行距均 ≤3）
    CandidateFinding a = finding("F.java", FindingCategory.BUG, 10, Severity.NIT, 0.5);
    CandidateFinding b = finding("F.java", FindingCategory.BUG, 12, Severity.NIT, 0.5);
    CandidateFinding c = finding("F.java", FindingCategory.BUG, 14, Severity.NIT, 0.5);
    ExactDedupMerger.Result result = ExactDedupMerger.merge(List.of(a, b, c));
    assertThat(result.kept()).hasSize(1);
    assertThat(result.mergedAway()).isEqualTo(2);
    assertThat(result.clusters()).isEqualTo(1);
  }

  @Test
  void representativePrefersImportantThenConfidenceThenLowerLine() {
    CandidateFinding nitHighConf = finding("F.java", FindingCategory.BUG, 10, Severity.NIT, 0.99);
    CandidateFinding importantLowConf =
        finding("F.java", FindingCategory.BUG, 12, Severity.IMPORTANT, 0.1);
    CandidateFinding importantNullConf =
        finding("F.java", FindingCategory.BUG, 11, Severity.IMPORTANT, null);
    ExactDedupMerger.Result result =
        ExactDedupMerger.merge(List.of(nitHighConf, importantLowConf, importantNullConf));
    assertThat(result.kept().get(0).id()).isEqualTo(importantLowConf.id()); // important 优先于 nit
    // 同为 important：confidence 0.1 > null
    assertThat(result.kept().get(0).severityProposed()).isEqualTo(Severity.IMPORTANT);
  }

  @Test
  void mergeIsDeterministicAcrossInputOrder() {
    CandidateFinding a = finding("F.java", FindingCategory.BUG, 10, Severity.NIT, 0.5);
    CandidateFinding b = finding("F.java", FindingCategory.BUG, 11, Severity.IMPORTANT, 0.5);
    String first = ExactDedupMerger.merge(List.of(a, b)).kept().get(0).id();
    String second = ExactDedupMerger.merge(List.of(b, a)).kept().get(0).id();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void resultToleratesNullMapAndNullValues() {
    ExactDedupMerger.Result empty = new ExactDedupMerger.Result(List.of(), null, 0, 0);
    assertThat(empty.mergedFrom()).isEmpty();

    // Map.of 拒绝 null 值，须用 HashMap 才能真正走进值空防御分支
    Map<String, List<String>> withNullValue = new HashMap<>();
    withNullValue.put("f_x", null);
    ExactDedupMerger.Result tolerant = new ExactDedupMerger.Result(null, withNullValue, 0, 0);
    assertThat(tolerant.kept()).isEmpty();
    assertThat(tolerant.mergedFrom()).containsEntry("f_x", List.of());
  }
}
