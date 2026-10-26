package org.cra.store.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewKey;
import org.cra.common.run.ReviewMode;
import org.junit.jupiter.api.Test;

/** 无 PR 载体账本（M4c/M4d，05§1.3）：commit / issue 键的作用域与过滤。 */
class GitHubCarrierLedgerTest {

  private static final RepoRef REPO = new RepoRef("o", "r");

  private static final LedgerRunMarker DONE =
      new LedgerRunMarker("run_1", "abc1234", "DONE", 2, 0, 9001L);

  private static CommentLedger ledgerFor(String sha) {
    LedgerComment comment =
        new LedgerComment(
            9001L,
            LedgerComment.KIND_COMMIT,
            "bot",
            "summary\n<!-- cra:v=2.1 | run=run_1 | commit=abc1234 | status=DONE | findings=2 "
                + "| suppressed=0 -->",
            Instant.parse("2026-10-26T00:00:00Z"),
            sha);
    return new CommentLedger(List.of(comment), List.of(), List.of(DONE), 0);
  }

  @Test
  void commitLedgerReadsCommitScopedCarrier() {
    String[] asked = new String[1];
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            throw new AssertionError("commit 形态不应触达 PR 端点");
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }

          @Override
          public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
            asked[0] = commitSha;
            return ledgerFor(commitSha);
          }
        };
    GitHubCarrierLedger ledger = new GitHubCarrierLedger(source, REPO, ReviewMode.COMMIT);
    ReviewKey key = ReviewKey.commit("o", "r", "abc1234");
    assertThat(ledger.findCompletion(key)).contains(DONE);
    assertThat(asked[0]).isEqualTo("abc1234");
    assertThat(ledger.completedRuns(key)).containsExactly(DONE);
    assertThat(ledger.comment(key, 9001L)).isPresent();
    assertThat(ledger.comment(key, 1L)).isEmpty();
    assertThat(ledger.publishedFindings(key)).isEmpty();
  }

  @Test
  void commitLedgerFiltersDifferentSha() {
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            throw new AssertionError();
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }

          @Override
          public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
            // 端点返回同 commit 评论；标记内 commit 仍是 abc1234
            return ledgerFor(commitSha);
          }
        };
    GitHubCarrierLedger ledger = new GitHubCarrierLedger(source, REPO, ReviewMode.COMMIT);
    assertThat(ledger.findCompletion(ReviewKey.commit("o", "r", "zzz9999"))).isEmpty();
  }

  @Test
  void issueLedgerUsesIssueCarrierEndpoint() {
    String[] asked = new String[1];
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            throw new AssertionError("issue 形态不应触达 PR 端点");
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }

          @Override
          public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
            asked[0] = commitSha;
            return ledgerFor(commitSha);
          }
        };
    GitHubCarrierLedger ledger = new GitHubCarrierLedger(source, REPO, ReviewMode.ISSUE);
    ReviewKey key = ReviewKey.issue("o", "r", 42, "abc1234");
    assertThat(ledger.findCompletion(key)).contains(DONE);
    assertThat(asked[0]).isEqualTo("abc1234");
  }

  @Test
  void prLedgerRejectsNonPullRequestKey() {
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            return new CommentLedger(List.of(), List.of(), List.of(), 0);
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }
        };
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThatThrownBy(() -> ledger.findCompletion(ReviewKey.commit("o", "r", "abc1234")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PULL_REQUEST");
  }

  @Test
  void carrierLedgerRejectsPrShapeAndRepoMismatch() {
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            return new CommentLedger(List.of(), List.of(), List.of(), 0);
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }
        };
    assertThatThrownBy(() -> new GitHubCarrierLedger(source, REPO, ReviewMode.PULL_REQUEST))
        .isInstanceOf(IllegalArgumentException.class);
    GitHubCarrierLedger ledger = new GitHubCarrierLedger(source, REPO, ReviewMode.COMMIT);
    assertThatThrownBy(() -> ledger.findCompletion(ReviewKey.commit("x", "r", "abc1234")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("仓库不匹配");
  }

  @Test
  void carrierLedgerIntKeyMethodsAreEmpty() {
    CommentSource source =
        new CommentSource() {
          @Override
          public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
            return new CommentLedger(List.of(), List.of(), List.of(), 0);
          }

          @Override
          public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
            return new DispositionSignal[0];
          }
        };
    GitHubCarrierLedger ledger = new GitHubCarrierLedger(source, REPO, ReviewMode.ISSUE);
    assertThat(ledger.publishedFindings(7)).isEmpty();
    assertThat(ledger.completedRuns(7)).isEmpty();
    assertThat(ledger.suspectMarkerCount(7)).isZero();
    assertThat(ledger.comment(7, 1L)).isEmpty();
  }
}
