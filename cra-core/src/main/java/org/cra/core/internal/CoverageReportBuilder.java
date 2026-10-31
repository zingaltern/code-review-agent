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

  /**
   * 覆盖声明行（07 T1.4/02§4.4）。
   *
   * @param plan 深度计划与分块计划
   * @param diff 评审范围 diff
   * @param policy 评审策略
   * @param outcome 评审阶段结果
   * @param blameOutcome blame 结果
   * @param policyModified 策略是否被修改
   * @param aggregation 发现聚合结果
   * @return 覆盖声明行
   */
  static List<String> lines(
      ReviewPlan plan,
      DiffResult diff,
      ReviewPolicy policy,
      ReviewStage.StageOutcome outcome,
      PreExistingBlameChecker.Outcome blameOutcome,
      boolean policyModified,
      FindingAggregator.Aggregation aggregation) {
    int filesReviewed = plan.chunkPlan().chunks().stream().mapToInt(c -> c.files().size()).sum();
    return SummarySections.lines(
        new CoverageInput(
            plan.depthPlan(),
            diff.files().size(),
            filesReviewed,
            plan.chunkPlan().skippedPaths(),
            outcome.contextNotes(),
            outcome.trimDecisions(),
            plan.chunkPlan().notes(),
            PolicySections.warnings(policy, policyModified),
            blameOutcome.notes(),
            aggregation.nitDropped(),
            aggregation.suppressedByTruncation(),
            plan.chunkPlan().skippedTestCount()));
  }

  /**
   * 深度计划与分块计划的成对参数组（S107：两者恒同时出现，属同一评审计划快照）。
   *
   * @param depthPlan 深度计划
   * @param chunkPlan 分块计划
   */
  record ReviewPlan(DepthPlan depthPlan, ChunkPlanner.Plan chunkPlan) {}

  static String note(List<String> coverageLines) {
    return String.join("；", coverageLines);
  }
}
