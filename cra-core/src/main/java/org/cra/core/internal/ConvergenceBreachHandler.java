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

  static ReviewResult handle(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      ReviewPublisher publisher,
      DiffResult diff,
      String suspectWarning,
      ConvergenceGuard.RoundPlan roundPlan,
      long start,
      SignoffAdvice signoff) {
    List<String> breachCoverage =
        new ArrayList<>(List.of("第 " + roundPlan.round() + " 轮：验证-复审循环已超上限，本轮停止评审"));
    publisher.publish(
        request,
        runId,
        trace,
        List.of(),
        0,
        0,
        new ArrayList<>(List.of("convergence-round-limit")),
        false,
        suspectWarning,
        breachCoverage,
        ConvergenceGuard.breachAdvice(),
        Set.of(),
        0,
        false,
        Map.of(),
        signoff);
    return ReviewRunFinisher.finish(
        trace,
        runId,
        request,
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
            System.currentTimeMillis() - start,
            List.of("convergence-round-limit"),
            0,
            new Coverage(diff.files().size(), 0, "轮次超限，停止评审")),
        "run=" + runId + " 轮次超限（第 " + roundPlan.round() + " 轮），停止评审并建议打回重新设计",
        List.of(),
        signoff);
  }
}
