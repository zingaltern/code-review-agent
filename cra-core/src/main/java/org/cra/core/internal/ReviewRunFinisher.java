package org.cra.core.internal;

import static org.cra.core.internal.TraceEvents.RUN_END;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.FindingCategory;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.ReviewSuggestion;
import org.cra.common.run.RunStatus;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;

/**
 * run 收尾与结果组装（02§4.2/05§1.4）：run id、run_end trace、ReviewResult、建议快照与空统计。
 *
 * <p>从 {@link ReviewPipeline} 拆出：所有终态（SKIPPED/FAILED/DEGRADED/SUCCESS）都经同一出口， 避免收尾逻辑分散。
 */
final class ReviewRunFinisher {

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private ReviewRunFinisher() {}

  static String newRunId() {
    return "run_"
        + LocalDateTime.now(ZoneId.systemDefault()).format(TS)
        + "-"
        + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
  }

  static ReviewResult finish(
      TraceWriter trace,
      String runId,
      ReviewRequest request,
      RunStatus status,
      ReviewStats stats,
      String summaryLine) {
    return finish(trace, runId, request, status, stats, summaryLine, List.of());
  }

  static ReviewResult finish(
      TraceWriter trace,
      String runId,
      ReviewRequest request,
      RunStatus status,
      ReviewStats stats,
      String summaryLine,
      List<ReviewSuggestion> suggestions) {
    return finish(
        trace,
        runId,
        request,
        new FinishOutcome(
            status,
            stats,
            summaryLine,
            suggestions,
            SignoffAdvice.disabled(request.headSha(), request.signoff())));
  }

  /**
   * 写 run_end trace 并组装结果（唯一出口）。
   *
   * @param trace 追踪写入
   * @param runId run 标识
   * @param request 评审请求
   * @param outcome 收尾内容（状态/统计/摘要/建议/签字）
   * @return 评审结果
   */
  static ReviewResult finish(
      TraceWriter trace, String runId, ReviewRequest request, FinishOutcome outcome) {
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("status", outcome.status().name().toLowerCase(Locale.ROOT));
    end.put("findings_published", outcome.stats().findingsPublished());
    end.put("skipped_duplicate", outcome.stats().skippedDuplicate());
    end.put("suppressed_by_truncation", outcome.stats().suppressedByTruncation());
    end.put("tokens_in", outcome.stats().tokensIn());
    end.put("tokens_out", outcome.stats().tokensOut());
    end.put("latency_ms", outcome.stats().latencyMs());
    trace.emit(RUN_END, end);
    return new ReviewResult(
        runId,
        request.repoOwner(),
        request.repoName(),
        request.prNumber(),
        request.headSha(),
        outcome.status(),
        outcome.stats(),
        "file://" + trace.runDir(),
        outcome.summaryLine(),
        outcome.suggestions(),
        outcome.signoff());
  }

  /**
   * 收尾内容（状态/统计/摘要/建议/签字）。
   *
   * @param status 终态
   * @param stats 统计
   * @param summaryLine 摘要行
   * @param suggestions 建议清单
   * @param signoff 签字建议
   */
  record FinishOutcome(
      RunStatus status,
      ReviewStats stats,
      String summaryLine,
      List<ReviewSuggestion> suggestions,
      SignoffAdvice signoff) {}

  /** 聚合后建议快照（本地 pre-PR 报告的建议清单数据源，02§5 本地形态；CI 形态不消费）。 */
  static List<ReviewSuggestion> suggestionsOf(List<FindingAggregator.Scored> ordered) {
    return ordered.stream()
        .map(
            s ->
                new ReviewSuggestion(
                    s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING
                        ? "PRE-EXISTING"
                        : s.finding().severityProposed().name(),
                    s.finding().title(),
                    s.finding().file(),
                    s.finding().line(),
                    s.finding().description(),
                    s.finding().suggestion() == null ? "" : s.finding().suggestion()))
        .toList();
  }

  static ReviewStats emptyStats(long latencyMs) {
    return new ReviewStats(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, latencyMs, List.of(), 0, new Coverage(0, 0, ""));
  }

  /** 发布集合的计数快照（Summary/stats 口径，02§4.4）。 */
  record PublishCounters(
      int fallbackCount, int preExistingCount, int filesReviewed, int securityCount) {}

  static PublishCounters counters(
      List<FindingAggregator.Scored> toPublish, ChunkPlanner.Plan plan) {
    int fallbackCount =
        (int) toPublish.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).count();
    int preExistingCount =
        (int)
            toPublish.stream()
                .filter(s -> s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING)
                .count();
    int filesReviewed = plan.chunks().stream().mapToInt(c -> c.files().size()).sum();
    int securityCount =
        (int)
            toPublish.stream()
                .filter(s -> s.finding().category() == FindingCategory.SECURITY)
                .count();
    return new PublishCounters(fallbackCount, preExistingCount, filesReviewed, securityCount);
  }

  /**
   * 发布集合的统计快照（Summary/stats 口径，02§4.4）。
   *
   * @param counts run 级发现计数
   * @param counters 发布集合计数
   * @param suppressedByTruncation 截断抑制数
   * @param usage token/耗时
   * @param degradations 降级记录
   * @param coverage 覆盖声明
   * @return 统计
   */
  static ReviewStats stats(
      RunCounts counts,
      PublishCounters counters,
      int suppressedByTruncation,
      RunUsage usage,
      List<String> degradations,
      CoverageInfo coverage) {
    return new ReviewStats(
        counts.candidateCount(),
        counts.publishedCount(),
        counts.skippedDuplicate(),
        counts.confirmedCount(),
        counts.rejectedCount(),
        counts.unverifiedCount(),
        counters.fallbackCount(),
        counters.preExistingCount(),
        suppressedByTruncation,
        usage.tokensIn(),
        usage.tokensOut(),
        usage.latencyMs(),
        degradations,
        counters.securityCount(),
        new Coverage(coverage.changedFiles(), counters.filesReviewed(), coverage.coverageNote()));
  }

  /**
   * run 级发现计数。
   *
   * @param candidateCount 候选数
   * @param publishedCount 发布数
   * @param skippedDuplicate 去重跳过数
   * @param confirmedCount 确认数
   * @param rejectedCount 拒绝数
   * @param unverifiedCount 未验证数
   */
  record RunCounts(
      int candidateCount,
      int publishedCount,
      int skippedDuplicate,
      int confirmedCount,
      int rejectedCount,
      int unverifiedCount) {}

  /**
   * token 与耗时。
   *
   * @param tokensIn 输入 token
   * @param tokensOut 输出 token
   * @param latencyMs 耗时
   */
  record RunUsage(long tokensIn, long tokensOut, long latencyMs) {}

  /**
   * 覆盖声明。
   *
   * @param changedFiles 变更文件数
   * @param coverageNote 覆盖说明
   */
  record CoverageInfo(int changedFiles, String coverageNote) {}

  static ReviewStats llmUnavailableStats(
      long tokensIn, long tokensOut, long latencyMs, List<String> degradations, int changedFiles) {
    return new ReviewStats(
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        tokensIn,
        tokensOut,
        latencyMs,
        degradations,
        0,
        new Coverage(changedFiles, 0, "LLM 不可得，未评审"));
  }

  static String summaryLine(
      String runId,
      RunStatus status,
      String depth,
      ReviewStats stats,
      int confirmedCount,
      int rejectedCount,
      int unverifiedCount) {
    return String.format(
        "run=%s status=%s depth=%s candidate=%d confirmed=%d rejected=%d unverified=%d "
            + "published=%d dedup=%d fallback=%d suppressed=%d tokens=%d/%d",
        runId,
        status,
        depth,
        stats.findingsCandidate(),
        confirmedCount,
        rejectedCount,
        unverifiedCount,
        stats.findingsPublished(),
        stats.skippedDuplicate(),
        stats.anchorFallback(),
        stats.suppressedByTruncation(),
        stats.tokensIn(),
        stats.tokensOut());
  }
}
