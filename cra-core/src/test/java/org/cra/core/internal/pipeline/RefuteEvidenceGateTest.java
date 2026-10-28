package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.cra.common.finding.FindingCategory;
import org.cra.core.internal.VerdictJsonCodec.Refutation;
import org.cra.core.internal.VerdictJsonCodec.RefutationKind;
import org.cra.core.internal.VerdictJsonCodec.VerifierVerdict;
import org.junit.jupiter.api.Test;

/** REFUTED 反证机械校验（ADR-0015 §2.1）：「无反证 → 不得 REFUTED（应 INSUFFICIENT）」的核心单测。 */
class RefuteEvidenceGateTest {

  private static final String CONTEXT =
      """
      文件全文（src/Foo.java）：
      public class Foo {
        int get(List<Integer> items) {
          if (items == null) {
            return 0;
          }
          return items.get(items.size() - 1);
        }
      }
      """;

  private static Refutation refutation(RefutationKind kind, String anchor, String quote) {
    return new Refutation(kind, anchor, quote, "reason", false);
  }

  @Test
  void bugRefutedWithoutRefutationDowngradesToInsufficient() {
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(FindingCategory.BUG, VerifierVerdict.REFUTED, null, CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.MISSING);
    assertThat(result.downgraded()).isTrue();
  }

  @Test
  void citationMismatchIsNotARefutationOfFailureMode() {
    // JacksonCore-8 形态：引述/方法名不符不构成反证（即使 quote 逐字可定位）
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(
            FindingCategory.BUG,
            VerifierVerdict.REFUTED,
            refutation(
                RefutationKind.CITATION_MISMATCH,
                "src/Foo.java:4",
                "if (items == null) {\n  return 0;"),
            CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.CITATION_MISMATCH);
  }

  @Test
  void quoteOutsideBriefIsNotCheckable() {
    // 对简报外代码的推测（如"调用方不会……"）无法逐字定位 → 降级
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(
            FindingCategory.BUG,
            VerifierVerdict.REFUTED,
            refutation(
                RefutationKind.GUARD_EXISTS,
                "src/Caller.java:10",
                "Objects.requireNonNull(items);"),
            CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.QUOTE_UNRESOLVED);
  }

  @Test
  void shortQuoteHasNoDiscriminatingPower() {
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(
            FindingCategory.BUG,
            VerifierVerdict.REFUTED,
            refutation(RefutationKind.GUARD_EXISTS, "src/Foo.java:4", "null"),
            CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.QUOTE_TOO_SHORT);
  }

  @Test
  void missingAnchorDowngrades() {
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(
            FindingCategory.BUG,
            VerifierVerdict.REFUTED,
            refutation(RefutationKind.GUARD_EXISTS, "  ", "if (items == null) {"),
            CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.ANCHOR_MISSING);
  }

  @Test
  void resolvableGuardEvidenceKeepsRefuted() {
    // 归一化容忍换行/缩进/反引号差异（模型引用代码的常见形态），但仍要求逐字
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(
            FindingCategory.BUG,
            VerifierVerdict.REFUTED,
            refutation(
                RefutationKind.GUARD_EXISTS,
                "src/Foo.java:3",
                "```\nif (items == null) {  return 0;\n```"),
            CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.OK);
    assertThat(result.downgraded()).isFalse();
  }

  @Test
  void nonBugCategoriesAndNonRefutedVerdictsAreNotApplicable() {
    assertThat(
            RefuteEvidenceGate.check(
                    FindingCategory.SECURITY, VerifierVerdict.REFUTED, null, CONTEXT)
                .status())
        .isEqualTo(RefuteEvidenceGate.Status.NOT_APPLICABLE);
    assertThat(
            RefuteEvidenceGate.check(FindingCategory.BUG, VerifierVerdict.CONFIRMED, null, CONTEXT)
                .status())
        .isEqualTo(RefuteEvidenceGate.Status.NOT_APPLICABLE);
    assertThat(
            RefuteEvidenceGate.check(
                    FindingCategory.BUG, VerifierVerdict.UNAVAILABLE, null, CONTEXT)
                .status())
        .isEqualTo(RefuteEvidenceGate.Status.NOT_APPLICABLE);
  }

  @Test
  void malformedRefutationDowngradesAsMissing() {
    Refutation malformed = new Refutation(null, "src/Foo.java:4", "x", "r", true);
    RefuteEvidenceGate.Result result =
        RefuteEvidenceGate.check(FindingCategory.BUG, VerifierVerdict.REFUTED, malformed, CONTEXT);
    assertThat(result.status()).isEqualTo(RefuteEvidenceGate.Status.DOWNGRADE);
    assertThat(result.reason()).isEqualTo(RefuteEvidenceGate.Reason.MISSING);
  }
}
