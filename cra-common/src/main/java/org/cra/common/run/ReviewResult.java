package org.cra.common.run;

import java.util.List;
import java.util.Objects;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffContext;

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
 * @param signoffAdvice 签字建议（M4a；绑定本次 head commit，永远不是审批——agent 不是 approver）
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
    List<ReviewSuggestion> suggestions,
    SignoffAdvice signoffAdvice) {

  public ReviewResult {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stats, "stats");
    traceRef = traceRef == null ? "" : traceRef;
    summaryLine = summaryLine == null ? "" : summaryLine;
    suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
    if (signoffAdvice == null) {
      // 无建议时保持可读的显式 disabled 形态（而不是 null），消费方无需空判
      signoffAdvice = SignoffAdvice.disabled(boundCommit(commitSha), SignoffContext.unknown());
    }
  }

  private static String boundCommit(String commitSha) {
    return commitSha == null || commitSha.isBlank() ? "unknown" : commitSha;
  }

  /** M0/M1 形态兼容构造（无建议快照；签字建议 disabled）。 */
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
        List.of(),
        SignoffAdvice.disabled(boundCommit(commitSha), SignoffContext.unknown()));
  }

  /** T2.10 形态兼容构造（有建议快照；签字建议 disabled）。 */
  public ReviewResult(
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
        suggestions,
        SignoffAdvice.disabled(boundCommit(commitSha), SignoffContext.unknown()));
  }
}
