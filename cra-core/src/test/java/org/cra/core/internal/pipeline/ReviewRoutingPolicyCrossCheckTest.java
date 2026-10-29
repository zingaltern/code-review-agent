package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.cra.agents.api.CrossCheckerDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.run.Depth;
import org.cra.core.api.CrossCheckerOptions;
import org.junit.jupiter.api.Test;

/**
 * CrossChecker 触发判定的数据可得性与未触发原因（03§3.1 / 07 T1.4 覆盖声明）。
 *
 * <p>评审发现（PR #2 审计 A7）：REVIEW.md 无产品段且变更史/事件史为空、以及 sniff-only 与配置 mode 不匹配两条 false
 * 分支此前零覆盖——本测试锁定跳过原因文案，防触发判定与声明口径漂移。
 */
class ReviewRoutingPolicyCrossCheckTest {

  private static final DepthPlan STANDARD =
      new DepthPlan(Depth.STANDARD, 15, false, 20, false, List.of());
  private static final DepthPlan QUICK =
      new DepthPlan(Depth.QUICK, 30, false, 20, false, List.of());

  private static ReviewCrew crewWithCrossChecker() {
    return new ReviewCrew(
        List.of(), null, null, null, null, new CrossCheckerDefinition(new TemplateLoader()));
  }

  private static ReviewCrew crewWithoutCrossChecker() {
    return new ReviewCrew(List.of(), null, null, null, null, null);
  }

  private static CrossCheckerOptions bothModes() {
    return new CrossCheckerOptions(
        true, Set.of(CrossCheckerOptions.Mode.ASSUMPTION, CrossCheckerOptions.Mode.SNIFF), 8);
  }

  private static ReviewPolicy sniffOnlyPolicy() {
    return new ReviewPolicy(
        true,
        List.of(),
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("遇到金额计算必须核对舍入口径"));
  }

  @Test
  void noDataSkipsWithSectionMissingReason() {
    ReviewPolicy empty = ReviewPolicy.empty();
    assertThat(
            ReviewRoutingPolicy.crossCheckTriggered(
                STANDARD, empty, crewWithCrossChecker(), bothModes(), CrossCheckHistory.none()))
        .isFalse();
    assertThat(
            ReviewRoutingPolicy.crossCheckSkipReason(
                STANDARD, empty, crewWithCrossChecker(), CrossCheckHistory.none()))
        .contains("REVIEW.md 未配置 Product context/Known incidents/Sniff tests")
        .contains("变更史/事件史为空或不可得");
  }

  @Test
  void sniffOnlyDataTriggersOnlyWhenSniffModeEnabled() {
    ReviewPolicy sniffOnly = sniffOnlyPolicy();
    assertThat(
            ReviewRoutingPolicy.crossCheckTriggered(
                STANDARD, sniffOnly, crewWithCrossChecker(), bothModes(), CrossCheckHistory.none()))
        .isTrue();
    CrossCheckerOptions assumptionOnly =
        new CrossCheckerOptions(true, Set.of(CrossCheckerOptions.Mode.ASSUMPTION), 8);
    assertThat(
            ReviewRoutingPolicy.crossCheckTriggered(
                STANDARD,
                sniffOnly,
                crewWithCrossChecker(),
                assumptionOnly,
                CrossCheckHistory.none()))
        .isFalse();
    assertThat(
            ReviewRoutingPolicy.crossCheckSkipReason(
                STANDARD,
                sniffOnly,
                crewWithCrossChecker(),
                CrossCheckHistory.none(),
                CrossCheckEvents.none()))
        .contains("配置的 mode 与可用数据不匹配");
  }

  @Test
  void quickDepthNeverTriggersWithDeclaredReason() {
    assertThat(
            ReviewRoutingPolicy.crossCheckTriggered(
                QUICK,
                sniffOnlyPolicy(),
                crewWithCrossChecker(),
                bothModes(),
                CrossCheckHistory.none()))
        .isFalse();
    assertThat(
            ReviewRoutingPolicy.crossCheckSkipReason(
                QUICK, sniffOnlyPolicy(), crewWithCrossChecker(), CrossCheckHistory.none()))
        .contains("quick 档不触发");
  }

  @Test
  void crewWithoutCrossCheckerSkipsWithDeclaredReason() {
    assertThat(
            ReviewRoutingPolicy.crossCheckTriggered(
                STANDARD,
                sniffOnlyPolicy(),
                crewWithoutCrossChecker(),
                bothModes(),
                CrossCheckHistory.none()))
        .isFalse();
    assertThat(
            ReviewRoutingPolicy.crossCheckSkipReason(
                STANDARD, sniffOnlyPolicy(), crewWithoutCrossChecker(), CrossCheckHistory.none()))
        .contains("班组未装配 cross-checker");
  }
}
