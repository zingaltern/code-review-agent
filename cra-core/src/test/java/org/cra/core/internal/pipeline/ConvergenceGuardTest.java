package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.LedgerRunMarker;
import org.junit.jupiter.api.Test;

/** 收敛控制（03§5：轮次上限 3、diff-only 复审基点、指纹去重由账本层承担）。 */
class ConvergenceGuardTest {

  private static LedgerRunMarker run(String runId, String sha) {
    return new LedgerRunMarker(runId, sha, "DONE", 1, 0, 1L);
  }

  @Test
  void firstReviewIsRoundOneWithFullDiff() {
    ConvergenceGuard.RoundPlan plan = ConvergenceGuard.resolve(List.of(), "head1");
    assertThat(plan.round()).isEqualTo(1);
    assertThat(plan.lastReviewedHead()).isEmpty();
    assertThat(plan.breached()).isFalse();
  }

  @Test
  void priorCompletedRunsBecomeDiffOnlyBase() {
    ConvergenceGuard.RoundPlan plan =
        ConvergenceGuard.resolve(List.of(run("r1", "sha1"), run("r2", "sha2")), "head");
    assertThat(plan.round()).isEqualTo(3);
    assertThat(plan.lastReviewedHead()).contains("sha2");
    assertThat(plan.breached()).isFalse(); // 第 3 轮仍允许
  }

  @Test
  void fourthRoundBreaches() {
    ConvergenceGuard.RoundPlan plan =
        ConvergenceGuard.resolve(
            List.of(run("r1", "sha1"), run("r2", "sha2"), run("r3", "sha3")), "head");
    assertThat(plan.round()).isEqualTo(4);
    assertThat(plan.breached()).isTrue();
  }

  @Test
  void sameHeadMarkersDoNotCountAsPriorRounds() {
    // 同 commit 重跑（半截补发）不是新轮次
    ConvergenceGuard.RoundPlan plan =
        ConvergenceGuard.resolve(List.of(run("r1", "sha1"), run("r2", "head")), "head");
    assertThat(plan.round()).isEqualTo(2);
    assertThat(plan.lastReviewedHead()).contains("sha1");
  }

  @Test
  void breachAdvicePrescribesRedesignNotMorePatching() {
    assertThat(ConvergenceGuard.breachAdvice()).contains("打回重新设计").contains("3 轮");
  }

  @Test
  void securityOrImportantScopeForSpotCheck() {
    CandidateFinding securityNit = finding(FindingCategory.SECURITY, Severity.NIT);
    CandidateFinding bugImportant = finding(FindingCategory.BUG, Severity.IMPORTANT);
    CandidateFinding bugNit = finding(FindingCategory.BUG, Severity.NIT);
    assertThat(ConvergenceGuard.securityOrImportant(securityNit)).isTrue();
    assertThat(ConvergenceGuard.securityOrImportant(bugImportant)).isTrue();
    assertThat(ConvergenceGuard.securityOrImportant(bugNit)).isFalse();
  }

  private static CandidateFinding finding(FindingCategory category, Severity severity) {
    return new CandidateFinding(
        "f_x",
        "run_t",
        category,
        "t",
        "d",
        "f",
        "F.java",
        1,
        Side.RIGHT,
        "e",
        null,
        severity,
        false,
        0.5,
        "bug-hunter/boundary",
        "m");
  }
}
