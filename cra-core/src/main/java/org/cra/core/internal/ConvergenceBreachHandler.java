package org.cra.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.git.DiffResult;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.core.internal.pipeline.ConvergenceGuard;

/** 轮次超限收尾（03§5）：停止新 finding、发布收口 Summary 并返回 SUCCESS（设计问题交回人）。 */
final class ConvergenceBreachHandler {

  private ConvergenceBreachHandler() {}

  /**
   * 轮次超限收尾。
   *
   * @param run run 上下文（请求/runId/trace/起点）
   * @param publish 发布上下文（publisher/diff/告警/轮次计划/签字建议）
   * @return SUCCESS 收尾结果
   */
  static ReviewResult handle(RunContext run, PublishContext publish) {
    List<String> breachCoverage =
        new ArrayList<>(List.of("第 " + publish.roundPlan().round() + " 轮：验证-复审循环已超上限，本轮停止评审"));
    publish
        .publisher()
        .publish(
            new ReviewPublisher.RunHeader(run.request(), run.runId(), run.trace()),
            new ReviewPublisher.FindingSet(List.of(), 0, 0, Set.of(), 0),
            new ReviewPublisher.SummaryMeta(
                new ArrayList<>(List.of("convergence-round-limit")),
                false,
                publish.suspectWarning(),
                breachCoverage,
                ConvergenceGuard.breachAdvice(),
                false),
            new ReviewPublisher.PublishMeta(Map.of(), publish.signoff()));
    return ReviewRunFinisher.finish(
        run.trace(),
        run.runId(),
        run.request(),
        new ReviewRunFinisher.FinishOutcome(
            RunStatus.SUCCESS,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                System.currentTimeMillis() - run.start(),
                List.of("convergence-round-limit"),
                0,
                new Coverage(publish.diff().files().size(), 0, "轮次超限，停止评审")),
            "run=" + run.runId() + " 轮次超限（第 " + publish.roundPlan().round() + " 轮），停止评审并建议打回重新设计",
            List.of(),
            publish.signoff()));
  }

  /**
   * run 级上下文。
   *
   * @param request 评审请求
   * @param runId run 标识
   * @param trace 追踪写入
   * @param start 起点毫秒
   */
  record RunContext(ReviewRequest request, String runId, TraceWriter trace, long start) {}

  /**
   * 发布级上下文。
   *
   * @param publisher 发布器
   * @param diff 变更集
   * @param suspectWarning 可疑标记告警
   * @param roundPlan 轮次计划
   * @param signoff 签字建议
   */
  record PublishContext(
      ReviewPublisher publisher,
      DiffResult diff,
      String suspectWarning,
      ConvergenceGuard.RoundPlan roundPlan,
      SignoffAdvice signoff) {}
}
