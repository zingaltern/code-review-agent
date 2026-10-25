package org.cra.common.run;

import java.util.Objects;

/**
 * run 终态结果（02§5：Orchestrator 输出）。
 *
 * @param runId run 标识
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号
 * @param commitSha 评审的 head sha
 * @param status 终态
 * @param stats 统计
 * @param traceRef trace 归宿（artifact://… 或本地路径）
 * @param summaryLine run 结束输出的 summary 指标行（02§6：findings/成本/耗时/覆盖率声明）
 */
public record ReviewResult(
    String runId,
    String repoOwner,
    String repoName,
    int prNumber,
    String commitSha,
    RunStatus status,
    ReviewStats stats,
    String traceRef,
    String summaryLine) {

  public ReviewResult {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stats, "stats");
    traceRef = traceRef == null ? "" : traceRef;
    summaryLine = summaryLine == null ? "" : summaryLine;
  }
}
