package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.junit.jupiter.api.Test;

/** 验证窗口（02§4.4 / 04§2.2）：预算 ≥85% 或大 diff 时按 severity 只验证 top-10。 */
class FindingAggregatorTest {

  private static FindingAggregator.Scored scored(String id, Severity severity, String file, int line) {
    CandidateFinding finding =
        new CandidateFinding(
            id,
            "run_1",
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
            0.5,
            "bug-hunter/dataflow",
            "m");
    return new FindingAggregator.Scored(
        finding,
        "fp" + id,
        AnchorStatus.INLINE,
        PreExistingBlameChecker.Verdict.UNKNOWN);
  }

  @Test
  void validationWindowKeepsTenAndOrdersImportantFirst() {
    List<FindingAggregator.Scored> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(scored("f_nit" + i, Severity.NIT, "A.java", i + 1));
    }
    candidates.add(scored("f_imp", Severity.IMPORTANT, "Z.java", 99));
    List<FindingAggregator.Scored> window = FindingAggregator.validationWindow(candidates);
    assertThat(window).hasSize(FindingAggregator.TRUNCATION_LIMIT);
    assertThat(window.get(0).finding().id()).isEqualTo("f_imp");
  }

  @Test
  void validationWindowIsNoOpBelowLimit() {
    List<FindingAggregator.Scored> candidates =
        List.of(scored("f_1", Severity.IMPORTANT, "A.java", 1), scored("f_2", Severity.NIT, "B.java", 2));
    assertThat(FindingAggregator.validationWindow(candidates)).hasSize(2);
  }
}
