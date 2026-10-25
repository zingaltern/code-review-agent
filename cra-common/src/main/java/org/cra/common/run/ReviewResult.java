package org.cra.common.run;

import java.util.List;
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
 * @param suggestions 聚合后建议快照（本地 pre-PR 报告的建议清单数据源，02§5 本地形态；T2.10。 CI 形态消费方仍走发布侧 DTO，此字段为冗余但无害）
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
    String summaryLine,
    List<ReviewSuggestion> suggestions) {

  public ReviewResult {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stats, "stats");
    traceRef = traceRef == null ? "" : traceRef;
    summaryLine = summaryLine == null ? "" : summaryLine;
    suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
  }

  /** M0/M1 形态兼容构造（无建议快照）。 */
  public ReviewResult(
      String runId,
      String repoOwner,
      String repoName,
      int prNumber,
      String commitSha,
      RunStatus status,
      ReviewStats stats,
      String traceRef,
      String summaryLine) {
    this(
        runId,
        repoOwner,
        repoName,
        prNumber,
        commitSha,
        status,
        stats,
        traceRef,
        summaryLine,
        List.of());
  }
}
