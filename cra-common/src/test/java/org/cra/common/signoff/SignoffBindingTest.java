package org.cra.common.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.cra.common.git.LedgerRunMarker;
import org.junit.jupiter.api.Test;

/** 签字建议模型与 commit 绑定（07§5 M4a；05§1.3 DONE/指纹同源语义）。 */
class SignoffBindingTest {

  private static final String OLD = "0ld4c0de00000000000000000000000000000000";
  private static final String NEW = "newc0de000000000000000000000000000000000";

  private static SignoffAdvice advice(String commit) {
    return SignoffAdvice.advisory(
        RiskTier.MEDIUM,
        commit,
        new SignoffContext("alice", TestsStatus.PASSED),
        List.of(new SignoffRuleHit("business-logic", RiskTier.MEDIUM, "常规业务逻辑变更", List.of())));
  }

  private static LedgerRunMarker run(String commit) {
    return new LedgerRunMarker("run_" + commit.substring(0, 4), commit, "DONE", 1, 0, 11L);
  }

  @Test
  void validOnlyForTheBoundCommit() {
    SignoffAdvice advice = advice(OLD);
    assertThat(SignoffBinding.validFor(advice, OLD)).isTrue();
    assertThat(SignoffBinding.validFor(advice, OLD.toUpperCase())).isTrue();
    assertThat(SignoffBinding.validFor(advice, NEW)).isFalse();
    assertThat(SignoffBinding.validFor(SignoffBinding.markStale(advice, NEW), OLD)).isFalse();
    assertThat(SignoffBinding.validFor(SignoffAdvice.disabled(OLD, SignoffContext.unknown()), OLD))
        .isFalse();
  }

  @Test
  void supersedingCommitIsTheLatestDifferentRunAfterTheAdviceCommit() {
    assertThat(SignoffBinding.supersedingCommit(OLD, List.of())).isEmpty();
    assertThat(SignoffBinding.supersedingCommit(OLD, List.of(run(OLD)))).isEmpty();
    assertThat(SignoffBinding.supersedingCommit(OLD, List.of(run(OLD), run(NEW)))).contains(NEW);
    // 建议 commit 在更新轮次之后出现 → 建议仍是最新，不 stale
    assertThat(SignoffBinding.supersedingCommit(OLD, List.of(run(NEW), run(OLD)))).isEmpty();
    // 建议 commit 不在账本（尚未发布）→ 任何不同 commit 的完成标记都视为更新
    assertThat(SignoffBinding.supersedingCommit(OLD, List.of(run(NEW)))).contains(NEW);
  }

  @Test
  void markStaleKeepsIdentityAndIsIdempotent() {
    SignoffAdvice stale = SignoffBinding.markStale(advice(OLD), NEW);
    assertThat(stale.stale()).isTrue();
    assertThat(stale.commitSha()).isEqualTo(OLD);
    assertThat(stale.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(stale.staleReason()).contains(NEW).contains(OLD).contains("stale");
    assertThat(SignoffBinding.markStale(stale, "another")).isSameAs(stale);
    assertThatThrownBy(() -> SignoffBinding.markStale(advice(OLD), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tiersMapToSignerCounts() {
    assertThat(RiskTier.LOW.recommendedSigners()).isZero();
    assertThat(RiskTier.MEDIUM.recommendedSigners()).isEqualTo(1);
    assertThat(RiskTier.HIGH.recommendedSigners()).isEqualTo(2);
    assertThat(RiskTier.max(RiskTier.LOW, RiskTier.HIGH)).isEqualTo(RiskTier.HIGH);
    assertThat(RiskTier.max(null, RiskTier.MEDIUM)).isEqualTo(RiskTier.MEDIUM);
  }

  @Test
  void adviceIsNeverAnApproval() {
    SignoffAdvice advice = advice(OLD);
    assertThat(advice.advisoryOnly()).isTrue();
    assertThat(advice.approval()).isFalse();
    assertThat(advice.agentApprover()).isFalse();
    assertThat(SignoffAdvice.AGENT_IS_APPROVER).isFalse();
    assertThat(advice.decision()).isEqualTo(SignoffAdvice.DECISION_ADVISORY);
  }

  @Test
  void disabledAdviceCarriesNoTierOrSigners() {
    SignoffAdvice disabled = SignoffAdvice.disabled(OLD, SignoffContext.unknown());
    assertThat(disabled.applicable()).isFalse();
    assertThat(disabled.advisoryOnly()).isFalse();
    assertThat(disabled.tier()).isNull();
    assertThat(disabled.recommendedSigners()).isZero();
    assertThat(disabled.decision()).isEqualTo(SignoffAdvice.DECISION_DISABLED);
  }

  @Test
  void testsStatusParsesStrictly() {
    assertThat(TestsStatus.parse("passed")).isEqualTo(TestsStatus.PASSED);
    assertThat(TestsStatus.parse(" PASSED ")).isEqualTo(TestsStatus.PASSED);
    assertThat(TestsStatus.parse("failed")).isEqualTo(TestsStatus.FAILED);
    assertThat(TestsStatus.parse("")).isEqualTo(TestsStatus.UNKNOWN);
    assertThatThrownBy(() -> TestsStatus.parse("green"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
