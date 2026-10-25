package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.git.BlameResult;
import org.cra.core.internal.pipeline.PreExistingBlameChecker.Verdict;
import org.cra.git.api.LocalRepoContext;
import org.junit.jupiter.api.Test;

/** pre-existing blame 复核行为（07 T1.7，05§2）：PR commit 集内→嫌疑不成立；集外→确证；无本地仓库/blame 失败→UNKNOWN + 降级声明。 */
class PreExistingBlameCheckerTest {

  private static CandidateFinding suspect(String id) {
    return new CandidateFinding(
        id,
        "run_x",
        FindingCategory.BUG,
        "t",
        "d",
        "f",
        "src/Foo.java",
        4,
        null,
        "e",
        null,
        Severity.IMPORTANT,
        true,
        null,
        "general-reviewer",
        "glm-x");
  }

  @Test
  void blameOutsidePrCommitsConfirmsPreExisting() {
    LocalRepoContext repo = mock(LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.of("/w"));
    when(repo.blame(anyString(), anyInt(), anyString()))
        .thenReturn(new BlameResult("src/Foo.java", 4, "old-commit", "alice"));
    PreExistingBlameChecker checker = new PreExistingBlameChecker(repo);
    PreExistingBlameChecker.Outcome outcome =
        checker.check(List.of(suspect("f_1")), Set.of("pr-commit-1"), "head");
    assertThat(outcome.verdictOf("f_1")).isEqualTo(Verdict.CONFIRMED_PRE_EXISTING);
    assertThat(outcome.notes()).isEmpty();
  }

  @Test
  void blameInsidePrCommitsRejectsSuspect() {
    LocalRepoContext repo = mock(LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.of("/w"));
    when(repo.blame(anyString(), anyInt(), anyString()))
        .thenReturn(new BlameResult("src/Foo.java", 4, "pr-commit-1", "bob"));
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker(repo)
            .check(List.of(suspect("f_1")), Set.of("pr-commit-1"), "head");
    assertThat(outcome.verdictOf("f_1")).isEqualTo(Verdict.INTRODUCED_IN_PR);
  }

  @Test
  void noLocalRepoDegradesAllToUnknownWithNote() {
    LocalRepoContext repo = mock(LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.empty());
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker(repo)
            .check(List.of(suspect("f_1"), suspect("f_2")), Set.of(), "head");
    assertThat(outcome.verdictOf("f_1")).isEqualTo(Verdict.UNKNOWN);
    assertThat(outcome.verdictOf("f_2")).isEqualTo(Verdict.UNKNOWN);
    assertThat(outcome.notes()).anyMatch(n -> n.contains("无本地仓库") && n.contains("2 条"));
  }

  @Test
  void blameFailurePerFindingDegradesWithoutKillingRun() {
    LocalRepoContext repo = mock(LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.of("/w"));
    when(repo.blame(anyString(), anyInt(), anyString()))
        .thenThrow(new org.cra.common.exception.CraIntegrationException("file gone"));
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker(repo).check(List.of(suspect("f_1")), Set.of("pr"), "head");
    assertThat(outcome.verdictOf("f_1")).isEqualTo(Verdict.UNKNOWN);
    assertThat(outcome.notes()).anyMatch(n -> n.contains("blame 失败 1 条"));
  }

  @Test
  void nonSuspectsAreNotBlamed() {
    LocalRepoContext repo = mock(LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.of("/w"));
    CandidateFinding normal =
        new CandidateFinding(
            "f_9",
            "run_x",
            FindingCategory.BUG,
            "t",
            "d",
            "f",
            "src/Foo.java",
            4,
            null,
            "e",
            null,
            Severity.IMPORTANT,
            false,
            null,
            "general-reviewer",
            "glm-x");
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker(repo).check(List.of(normal), Set.of(), "head");
    assertThat(outcome.verdicts()).isEmpty();
    assertThat(outcome.notes()).isEmpty();
  }

  @Test
  void nullContextMeansPureApiMode() {
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker(null).check(List.of(suspect("f_1")), Set.of(), "head");
    assertThat(outcome.verdicts()).containsEntry("f_1", Verdict.UNKNOWN);
    assertThat(outcome.notes()).hasSize(1);
  }

  @Test
  void outcomeRecordDefensiveCopies() {
    PreExistingBlameChecker.Outcome outcome =
        new PreExistingBlameChecker.Outcome(Map.of("f_1", Verdict.UNKNOWN), List.of("n"));
    assertThat(outcome.verdictOf("f_1")).isEqualTo(Verdict.UNKNOWN);
    assertThat(outcome.verdictOf("missing")).isEqualTo(Verdict.UNKNOWN);
  }
}
