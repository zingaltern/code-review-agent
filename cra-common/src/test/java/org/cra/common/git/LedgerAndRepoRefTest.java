package org.cra.common.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 账本 POJO 校验（05§1.3）与 RepoRef 解析单测。 */
class LedgerAndRepoRefTest {

  @Test
  void runMarkerAcceptsDoneAndDegraded() {
    // 02§4.1 v1 基线修正：降级完成也是完整发布
    assertThat(new LedgerRunMarker("run_1", "abc1234", "DONE", 5, 0, 1L).isCompletion()).isTrue();
    assertThat(new LedgerRunMarker("run_1", "abc1234", "DEGRADED", 3, 1, 2L).isCompletion())
        .isTrue();
  }

  @Test
  void runMarkerRejectsOtherStatus() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LedgerRunMarker("run_1", "abc1234", "RUNNING", 0, 0, 1L));
  }

  @Test
  void runMarkerRejectsNegativeCounts() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LedgerRunMarker("run_1", "abc1234", "DONE", -1, 0, 1L));
  }

  @Test
  void findingMarkerRejectsNonHexFingerprint() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new LedgerFindingMarker(
                    "not-hex!", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L));
  }

  @Test
  void findingMarkerAcceptsTruncatedHex() {
    LedgerFindingMarker m =
        new LedgerFindingMarker(
            "9f2a1b", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L);
    assertThat(m.fingerprint()).isEqualTo("9f2a1b");
  }

  @Test
  void commentLedgerFallbackJudgement() {
    // 05§1.3 规则 5：无任何有效标记 → 退 commit 级闸
    assertThat(
            new CommentLedger(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0)
                .hasAnyValidMarker())
        .isFalse();
    assertThat(
            new CommentLedger(
                    java.util.List.of(),
                    java.util.List.of(
                        new LedgerFindingMarker("9f2a", "f", 1, "bug", "f_1", "run_1", "abc", 1L)),
                    java.util.List.of(),
                    0)
                .hasAnyValidMarker())
        .isTrue();
  }

  @Test
  void repoRefParsesOwnerSlashRepo() {
    RepoRef ref = RepoRef.parse("zingaltern/code-review-agent");
    assertThat(ref.owner()).isEqualTo("zingaltern");
    assertThat(ref.repo()).isEqualTo("code-review-agent");
    assertThatIllegalArgumentException().isThrownBy(() -> RepoRef.parse("just-a-name"));
    assertThatIllegalArgumentException().isThrownBy(() -> RepoRef.parse("a/b/c"));
  }

  @Test
  void ledgerCommentRejectsUnknownKind() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new LedgerComment(
                    1L, "other", "bot", "body", Instant.parse("2026-10-25T00:00:00Z"), null));
  }
}
