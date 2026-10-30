package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.junit.jupiter.api.Test;

/** 账本标记安全（05§1.3）：只有会进入 inline 评论的候选才需要路径安全性降级。 */
class ReviewPublisherTest {

  private static FindingAggregator.Scored scored(AnchorStatus anchor, String file) {
    CandidateFinding finding =
        new CandidateFinding(
            "f_1",
            "run_1",
            FindingCategory.BUG,
            "t",
            "d",
            "f",
            file,
            1,
            null,
            "e",
            null,
            Severity.IMPORTANT,
            false,
            0.5,
            "bug-hunter/dataflow",
            "m");
    return new FindingAggregator.Scored(
        finding, "fp1", anchor, PreExistingBlameChecker.Verdict.UNKNOWN);
  }

  @Test
  void inlineUnsafePathTriggersMarkerFallback() {
    assertThat(
            ReviewPublisher.hasMarkerUnsafeInline(List.of(scored(AnchorStatus.INLINE, "a|b.java"))))
        .isTrue();
  }

  @Test
  void fallbackUnsafePathDoesNotTriggerMarkerFallback() {
    assertThat(
            ReviewPublisher.hasMarkerUnsafeInline(
                List.of(scored(AnchorStatus.SUMMARY_FALLBACK, "a|b.java"))))
        .isFalse();
  }
}
