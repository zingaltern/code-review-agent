package org.cra.common.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cra.common.run.Depth;
import org.cra.common.run.ReviewMode;
import org.cra.common.run.ReviewRequest;
import org.junit.jupiter.api.Test;

/** 账本键控扩展（M4c/M4d，05§1.3）：PR 键 vs 无 PR 键的构造与校验。 */
class ReviewKeyTest {

  @Test
  void pullRequestKeyCarriesPrAndCommit() {
    ReviewKey key = ReviewKey.pullRequest("o", "r", 7, "abc1234");
    assertThat(key.mode()).isEqualTo(ReviewMode.PULL_REQUEST);
    assertThat(key.prNumber()).isEqualTo(7);
    assertThat(key.commitSha()).isEqualTo("abc1234");
  }

  @Test
  void commitKeyHasNoPrNumber() {
    ReviewKey key = ReviewKey.commit("o", "r", "abc1234");
    assertThat(key.mode()).isEqualTo(ReviewMode.COMMIT);
    assertThat(key.prNumber()).isZero();
  }

  @Test
  void issueKeyKeepsReviewedPrNumber() {
    ReviewKey key = ReviewKey.issue("o", "r", 42, "abc1234");
    assertThat(key.mode()).isEqualTo(ReviewMode.ISSUE);
    assertThat(key.prNumber()).isEqualTo(42);
  }

  @Test
  void commitModeRejectsPrNumber() {
    assertThatThrownBy(() -> new ReviewKey("o", "r", ReviewMode.COMMIT, 7, "abc1234"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无 PR 编号");
  }

  @Test
  void issueModeRequiresPrNumberButPullRequestAllowsLocalZero() {
    assertThatThrownBy(() -> new ReviewKey("o", "r", ReviewMode.ISSUE, 0, "abc1234"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("正数 PR 编号");
    // 本地 pre-PR 模式 pr=0 是合法形态（05§3：账本按 (repo, 0, commit) 键控）
    assertThat(ReviewKey.pullRequest("o", "r", 0, "abc1234").prNumber()).isZero();
  }

  @Test
  void blankCommitRejected() {
    assertThatThrownBy(() -> ReviewKey.commit("o", "r", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commitSha");
  }

  @Test
  void requestMapsModeToKey() {
    ReviewRequest push =
        new ReviewRequest(
            "o", "r", 0, "base", "head", Depth.STANDARD, false, false, ReviewMode.COMMIT);
    assertThat(push.key()).isEqualTo(ReviewKey.commit("o", "r", "head"));

    ReviewRequest nightly =
        new ReviewRequest(
            "o", "r", 42, "base", "head", Depth.STANDARD, false, false, ReviewMode.ISSUE);
    assertThat(nightly.key()).isEqualTo(ReviewKey.issue("o", "r", 42, "head"));

    ReviewRequest pr = new ReviewRequest("o", "r", 7, "base", "head", Depth.STANDARD, false, false);
    assertThat(pr.mode()).isEqualTo(ReviewMode.PULL_REQUEST);
    assertThat(pr.key()).isEqualTo(ReviewKey.pullRequest("o", "r", 7, "head"));

    ReviewRequest local =
        new ReviewRequest("o", "r", 0, "base", "head", Depth.STANDARD, true, false);
    assertThat(local.key()).isEqualTo(ReviewKey.pullRequest("o", "r", 0, "head"));
  }

  @Test
  void commitModeRejectsPrInRequest() {
    assertThatThrownBy(
            () ->
                new ReviewRequest(
                    "o", "r", 7, "base", "head", Depth.STANDARD, false, false, ReviewMode.COMMIT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无 PR 编号");
  }
}
