package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.signoff.RiskTier;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffContext;
import org.cra.common.signoff.SignoffRuleHit;
import org.cra.common.signoff.TestsStatus;
import org.junit.jupiter.api.Test;

/** Summary"签字建议"段渲染（07§5 M4a）：三档文案、commit 绑定、三条规矩声明与 stale 标记。 */
class SignoffSectionsTest {

  private static final String COMMIT = "abc1234000000000000000000000000000000000";

  private static SignoffAdvice high() {
    return SignoffAdvice.advisory(
        RiskTier.HIGH,
        COMMIT,
        new SignoffContext("zingaltern", TestsStatus.UNKNOWN),
        List.of(
            new SignoffRuleHit(
                "critical-config-path",
                RiskTier.HIGH,
                "命中难恢复的关键配置（部署/迁移/生产配置）",
                List.of(".github/workflows/ci.yml")),
            new SignoffRuleHit(
                "business-logic", RiskTier.MEDIUM, "常规业务逻辑变更", List.of("src/Foo.java"))));
  }

  @Test
  void rendersTierSignersCommitAndThreeRules() {
    String body = String.join("\n", SignoffSections.lines(high()));
    assertThat(body)
        .contains(SignoffSections.HEADING)
        .contains("风险分级：高风险 → 建议 2 人签字")
        .contains("绑定 commit：`abc1234`")
        .contains("approve 只对该 commit 有效")
        .contains("critical-config-path")
        .contains(".github/workflows/ci.yml")
        .contains("agent 的赞成票不计为审批")
        .contains("agent 永远不是 approver")
        .contains("本条仅为建议，approval 由人/平台策略决定")
        .contains("核实本次改动的意图、假设、设计，而非逐行读过")
        .contains("指挥 agent 写代码的人必须在签字名单上")
        .contains("作者 zingaltern")
        .contains("测试状态 unknown");
  }

  @Test
  void lowRiskRendersZeroSignerAdvice() {
    SignoffAdvice low =
        SignoffAdvice.advisory(
            RiskTier.LOW,
            COMMIT,
            new SignoffContext("zingaltern", TestsStatus.PASSED),
            List.of(
                new SignoffRuleHit("docs-only", RiskTier.LOW, "纯文档变更", List.of("docs/a.md")),
                new SignoffRuleHit(
                    "low-risk-conditions-met",
                    RiskTier.LOW,
                    "测试通过且作者可信（0 人签前提满足）",
                    List.of("author=zingaltern"))));
    String body = String.join("\n", SignoffSections.lines(low));
    assertThat(body).contains("风险分级：低风险 → 建议 0 人签字").contains("测试状态 passed");
  }

  @Test
  void disabledAdviceRendersNothing() {
    assertThat(SignoffSections.lines(SignoffAdvice.disabled(COMMIT, SignoffContext.unknown())))
        .isEmpty();
  }

  @Test
  void staleAdviceCarriesVisibleWarning() {
    SignoffAdvice stale =
        org.cra.common.signoff.SignoffBinding.markStale(high(), "def5678000000000000000000000000");
    String body = String.join("\n", SignoffSections.lines(stale));
    assertThat(body).contains("已 stale").contains("def5678");
  }

  @Test
  void evidenceIsFoldedAndEscaped() {
    SignoffAdvice advice =
        SignoffAdvice.advisory(
            RiskTier.MEDIUM,
            COMMIT,
            new SignoffContext("a<!--b", TestsStatus.UNKNOWN),
            List.of(
                new SignoffRuleHit(
                    "business-logic",
                    RiskTier.MEDIUM,
                    "常规业务逻辑变更",
                    List.of("a.java", "b.java", "c.java", "d.java", "e.java", "f.java"))));
    String body = String.join("\n", SignoffSections.lines(advice));
    assertThat(body).contains("等 6 个").contains("`a.java`").doesNotContain("<!--");
  }

  @Test
  void sectionDetectionAndStaleNoticeAreIdempotentHelpers() {
    String body = String.join("\n", SignoffSections.lines(high()));
    assertThat(SignoffSections.hasSignoffSection(body)).isTrue();
    assertThat(SignoffSections.hasStaleNotice(body)).isFalse();
    String notice = SignoffSections.staleNotice(COMMIT, "def5678000000000000000000000000");
    assertThat(notice).contains("签字建议已 stale").contains("abc1234").contains("def5678");
    assertThat(SignoffSections.hasStaleNotice(notice + body)).isTrue();
  }
}
