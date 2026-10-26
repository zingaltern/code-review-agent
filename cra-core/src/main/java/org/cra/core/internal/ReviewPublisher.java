package org.cra.core.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.run.ReviewRequest;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.store.api.ReviewLedger;

/**
 * 发布与账本写侧（05§2.2/02§5）：inline review → Summary issue comment → 完成标记/指纹落账 → 旧 Summary 过期收尾。
 *
 * <p>从 {@link ReviewPipeline} 拆出：编排器只决定“发什么”，本类负责“怎么发、怎么记账、怎么收尾”。dry-run 全程零写（05§6）。
 */
final class ReviewPublisher {

  /** 过期收尾前缀的判定起点（02§5：[已被 run_xxx 取代]；已收尾的评论不再叠加前缀）。 */
  private static final String SUPERSEDED_PREFIX = "[已被 run_";

  private final GitPlatformAdapter adapter;
  private final ReviewLedger ledger;

  ReviewPublisher(GitPlatformAdapter adapter, ReviewLedger ledger) {
    this.adapter = adapter;
    this.ledger = ledger;
  }

  int publish(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      List<FindingAggregator.Scored> ordered,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      String suspectWarning,
      List<String> coverageLines,
      String convergenceAdvice,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      Map<String, String> templateVersions,
      SignoffAdvice signoff) {
    List<FindingAggregator.Scored> fallbacks =
        ordered.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).toList();
    List<InlineComment> inline =
        ordered.stream()
            .filter(s -> s.anchor() == AnchorStatus.INLINE)
            .map(
                s ->
                    new InlineComment(
                        s.finding().file(),
                        s.finding().line(),
                        s.finding().side(),
                        ReviewBodyRenderer.inlineBody(s, runId, request.headSha(), unverifiedIds),
                        s.finding().id()))
            .toList();
    String summaryBody =
        ReviewBodyRenderer.summaryBody(
            request,
            runId,
            ordered,
            fallbacks,
            skippedDuplicate,
            suppressedByTruncation,
            degradations,
            degraded,
            suspectWarning,
            coverageLines,
            convergenceAdvice,
            unverifiedIds,
            unverifiedUnpublished,
            signoff);
    if (request.dryRun()) {
      // dry-run：全程零写（05§6），发布器不触达 adapter 与账本写侧
      trace.emit(
          "publish",
          Map.of(
              "dry_run",
              true,
              "comments",
              inline.size(),
              "skipped_duplicate",
              skippedDuplicate,
              "suppressed_by_truncation",
              suppressedByTruncation,
              "done_marker",
              false));
      return ordered.size();
    }
    String reviewBody =
        inline.isEmpty()
            ? "cra 评审（run_" + runId + "）：无可 inline 的发现，详见本 PR 的评审 Summary 评论。"
            : "cra 评审（run_"
                + runId
                + "）：发现 "
                + inline.size()
                + " 条问题（inline），详见本 PR 的评审 Summary 评论。";
    PostReviewCommand cmd =
        new PostReviewCommand(
            request.repoOwner(),
            request.repoName(),
            request.prNumber(),
            request.headSha(),
            reviewBody,
            inline,
            summaryBody);
    PrReviewResult result = adapter.postReview(cmd);
    if (result.items().stream().anyMatch(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
        && !inline.isEmpty()) {
      // 整包 422 逐条降级产生了额外 SUMMARY_FALLBACK（05§2.2），run 降级记录
      degradations.add("422-per-item-fallback");
    }
    long publishedActual =
        result.items().stream().filter(i -> i.publishStatus() == PublishStatus.PUBLISHED).count();
    long fallbackActual =
        result.items().stream()
            .filter(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
            .count();
    trace.emit(
        "publish",
        Map.of(
            "comments",
            result.items().size(),
            "skipped_duplicate",
            skippedDuplicate,
            "suppressed_by_truncation",
            suppressedByTruncation,
            "done_marker",
            result.summaryCommentId().isPresent()));
    if (result.summaryCommentId().isEmpty()) {
      // Summary 发布失败 = 无完成标记 = 不完整 run：不回写账本、不做过期收尾，重跑走指纹级补发（02§4.1）
      return (int) (publishedActual + fallbackActual);
    }
    recordToLedger(request, runId, ordered, result, skippedDuplicate, degraded, templateVersions);
    int supersedeFailures = supersedePreviousSummaries(request, runId, trace);
    if (supersedeFailures > 0) {
      degradations.add("summary-supersede-failed:x" + supersedeFailures);
    }
    return (int) (publishedActual + fallbackActual);
  }

  /**
   * 发布成功后账本化登记：完成标记 + 全部应发 finding（含 Summary 降级条目 commentId=0）。GitHub 形态 下为无操作（标记已内嵌评论）；SQLite
   * 形态由此获得幂等闸与补发的事实源（02§4.1/05§1.5）。
   */
  private void recordToLedger(
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      PrReviewResult result,
      int skippedDuplicate,
      boolean degraded,
      Map<String, String> templateVersions) {
    String status = degraded ? LedgerRunMarker.STATUS_DEGRADED : LedgerRunMarker.STATUS_DONE;
    long summaryCommentId = result.summaryCommentId().orElseThrow();
    ledger.recordRunCompletion(
        request.prNumber(),
        new LedgerRunMarker(
            runId, request.headSha(), status, ordered.size(), skippedDuplicate, summaryCommentId));
    // T2.11：模板版本随 run 落库（05§1.5 prompt_template_version；SQLite 形态覆写，GitHub 形态 default 无操作）
    ledger.recordTemplateVersions(request.prNumber(), runId, templateVersions);
    Map<String, Long> commentIds =
        result.items().stream()
            .filter(i -> i.commentId() != null)
            .collect(
                Collectors.toMap(
                    ReviewItemResult::findingId, ReviewItemResult::commentId, (a, b) -> a));
    for (FindingAggregator.Scored s : ordered) {
      ledger.recordPublishedFinding(
          request.prNumber(),
          new LedgerFindingMarker(
              s.fingerprint(),
              s.finding().file(),
              s.finding().line(),
              ReviewBodyRenderer.categoryOf(s.finding()),
              s.finding().id(),
              runId,
              request.headSha(),
              commentIds.getOrDefault(s.finding().id(), 0L)));
    }
  }

  /**
   * 过期收尾（02§5）：新 run 发布成功后，对同 PR 旧 head_sha 的 bot Summary issue comment 加前缀 {@code [已被 run_xxx
   * 取代]}（PATCH 端点，inline 评论不删——GitHub 自动标 outdated，处置采集依赖其在）。 幂等：已带取代前缀的不再叠加；body 不可得（SQLite 形态）或
   * PATCH 失败只入 trace/degradation， 不回滚完成标记（run 已完整发布）。
   *
   * @return PATCH 失败次数
   */
  private int supersedePreviousSummaries(ReviewRequest request, String runId, TraceWriter trace) {
    int failures = 0;
    for (LedgerRunMarker old : ledger.completedRuns(request.prNumber())) {
      if (old.commitSha().equalsIgnoreCase(request.headSha()) || old.commentId() <= 0) {
        continue;
      }
      String body =
          ledger.comment(request.prNumber(), old.commentId()).map(LedgerComment::body).orElse(null);
      if (body == null) {
        trace.emit(
            "summary_supersede", Map.of("comment_id", old.commentId(), "status", "unavailable"));
        continue;
      }
      if (body.startsWith(SUPERSEDED_PREFIX)) {
        trace.emit("summary_supersede", Map.of("comment_id", old.commentId(), "status", "skipped"));
        continue;
      }
      if (SignoffSections.hasSignoffSection(body) && !SignoffSections.hasStaleNotice(body)) {
        // 07§5 M4a 规矩 2：approve 只对特定 commit 有效——旧 Summary 的签字建议随新 commit 标记 stale
        // （账本比对：old.commitSha != 当前 head；声明追写在取代前缀之前，保留原正文）
        body = SignoffSections.staleNotice(old.commitSha(), request.headSha()) + "\n\n" + body;
      }
      try {
        adapter.updateSummaryComment(
            new RepoRef(request.repoOwner(), request.repoName()),
            request.prNumber(),
            old.commentId(),
            "[已被 " + runId + " 取代]\n\n" + body);
        trace.emit(
            "summary_supersede",
            Map.of("comment_id", old.commentId(), "status", "patched", "superseded_by", runId));
      } catch (RuntimeException e) {
        // 收尾失败不回滚完成标记（降级写状态，09§4）；次数计入 stats 供 Summary/健康度可见
        trace.emit(
            "summary_supersede",
            Map.of(
                "comment_id",
                old.commentId(),
                "status",
                "failed",
                "error",
                String.valueOf(e.getMessage())));
        failures++;
      }
    }
    return failures;
  }
}
