package org.cra.core.internal;

import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffResult;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PolicySections;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.cra.core.internal.pipeline.ReviewStage;
import org.cra.core.internal.pipeline.SummarySections;
import org.cra.core.internal.pipeline.SummarySections.CoverageInput;

/**
 * 覆盖声明组装（07 T1.4/02§4.4）：把深度、降档、裁剪、blame 与策略告警拼成 Summary 可见的覆盖行。
 *
 * <p>从 {@link ReviewPipeline} 拆出，保持“覆盖声明不静默”的单一入口。
 */
final class CoverageReportBuilder {

  private CoverageReportBuilder() {}

  static List<String> lines(
      DiffResult diff,
      ReviewPolicy policy,
      DepthPlan depthPlan,
      ChunkPlanner.Plan plan,
      ReviewStage.StageOutcome outcome,
      PreExistingBlameChecker.Outcome blameOutcome,
      boolean policyModified,
      FindingAggregator.Aggregation aggregation) {
    int filesReviewed = plan.chunks().stream().mapToInt(c -> c.files().size()).sum();
    return SummarySections.lines(
        new CoverageInput(
            depthPlan,
            diff.files().size(),
            filesReviewed,
            plan.skippedPaths(),
            outcome.contextNotes(),
            outcome.trimDecisions(),
            plan.notes(),
            PolicySections.warnings(policy, policyModified),
            blameOutcome.notes(),
            aggregation.nitDropped(),
            aggregation.suppressedByTruncation(),
            plan.skippedTestCount()));
  }

  static String note(List<String> coverageLines) {
    return String.join("；", coverageLines);
  }
}
