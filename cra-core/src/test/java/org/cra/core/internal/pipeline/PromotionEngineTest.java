package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.core.internal.VerdictJsonCodec;
import org.junit.jupiter.api.Test;

/** 判定与晋升规则（03§4.3 M2 形态：单验证者 + 仲裁 + 抽查保险丝）。 */
class PromotionEngineTest {

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

  @Test
  void confirmedPublishes() {
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.CONFIRMED,
            finding(FindingCategory.BUG, Severity.IMPORTANT),
            false);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.PUBLISH);
  }

  @Test
  void importantRefutedWithFuseGoesToJudge() {
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.REFUTED,
            finding(FindingCategory.BUG, Severity.IMPORTANT),
            true);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.JUDGE);
    assertThat(p.reason()).isEqualTo("refuted-spotcheck");
  }

  @Test
  void importantRefutedWithoutFuseRejected() {
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.REFUTED,
            finding(FindingCategory.BUG, Severity.IMPORTANT),
            false);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.REJECT);
  }

  @Test
  void securityRefutedIsInFuseScopeEvenAtNit() {
    // 03§4.3：security 类别与 important 同级进抽查（category 维度 OR severity 维度）
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.REFUTED,
            finding(FindingCategory.SECURITY, Severity.NIT),
            true);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.JUDGE);
  }

  @Test
  void insufficientAlwaysGoesToJudge() {
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.INSUFFICIENT_EVIDENCE,
            finding(FindingCategory.BUG, Severity.NIT),
            false);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.JUDGE);
    assertThat(p.reason()).isEqualTo("insufficient-arbitration");
  }

  @Test
  void unavailableIsUnverifiedNotPublished() {
    PromotionEngine.Promotion p =
        PromotionEngine.promote(
            VerdictJsonCodec.VerifierVerdict.UNAVAILABLE,
            finding(FindingCategory.BUG, Severity.IMPORTANT),
            false);
    assertThat(p.decision()).isEqualTo(PromotionEngine.Decision.UNVERIFIED);
  }
}
