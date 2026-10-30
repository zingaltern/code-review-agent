package org.cra.core.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.run.ReviewMode;
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
    ReviewMode mode = request.mode();
    boolean pullRequest = mode == ReviewMode.PULL_REQUEST;
    // 无 PR 载体（commit comment / issue）没有行级锚点：全部 finding 以详情进正文（05§2.4/§2.5），
    // 锚定校验结果仍如实进 stats/trace，但不作为发布分派依据
    boolean markerUnsafePath = pullRequest && hasMarkerUnsafeInline(ordered);
    List<FindingAggregator.Scored> fallbacks =
        pullRequest
            ? ordered.stream()
                .filter(
                    s ->
                        s.anchor() == AnchorStatus.SUMMARY_FALLBACK
                            || !ReviewBodyRenderer.markerSafePath(s.finding().file()))
                .toList()
            : List.of();
    List<InlineComment> inline =
        pullRequest
            ? ordered.stream()
                .filter(s -> s.anchor() == AnchorStatus.INLINE)
                .filter(s -> ReviewBodyRenderer.markerSafePath(s.finding().file()))
                .map(
                    s ->
                        new InlineComment(
                            s.finding().file(),
                            s.finding().line(),
                            s.finding().side(),
                            ReviewBodyRenderer.inlineBody(
                                s, runId, request.headSha(), unverifiedIds),
                            s.finding().id()))
                .toList()
            : List.of();
    if (markerUnsafePath && !degradations.contains("path-marker-unsafe")) {
      // 路径含 |/-->/换行会破坏账本标记（字段顺序严格校验），inline 降级为 Summary，幂等不静默失效
      degradations.add("path-marker-unsafe");
    }
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
            !pullRequest,
            signoff);
    if (request.dryRun()) {
      // dry-run：全程零写（05§6），发布器不触达 adapter 与账本写侧
      trace.emit(
          "publish",
          Map.of(
              "mode",
              mode.name().toLowerCase(java.util.Locale.ROOT),
              "dry_run",
              true,
              "comments",
              pullRequest ? inline.size() : 0,
              "skipped_duplicate",
              skippedDuplicate,
              "suppressed_by_truncation",
              suppressedByTruncation,
              "done_marker",
              false));
      return ordered.size();
    }
    if (mode == ReviewMode.COMMIT) {
      return publishCarrier(
          request,
          runId,
          trace,
          ordered,
          summaryBody,
          skippedDuplicate,
          suppressedByTruncation,
          degradations,
          degraded,
          templateVersions,
          "commit_comment");
    }
    if (mode == ReviewMode.ISSUE) {
      return publishCarrier(
          request,
          runId,
          trace,
          ordered,
          summaryBody,
          skippedDuplicate,
          suppressedByTruncation,
          degradations,
          degraded,
          templateVersions,
          "issue");
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
            "mode",
            "pull_request",
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
      // 必须显式降级：否则 run 报 SUCCESS、CI 退出 0，用户却看不到 Summary
      if (!degradations.contains("summary-publish-failed")) {
        degradations.add("summary-publish-failed");
      }
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
   * 无 PR 载体发布（M4c commit comment / M4d issue，05§2.4/§2.5）：单条原子载体，正文含全部 finding 详情与 run 完成标记。载体发布成功
   * = 完整发布（无半截状态），失败 = 无标记 = 重跑（02§4.1）。
   */
  private int publishCarrier(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      List<FindingAggregator.Scored> ordered,
      String summaryBody,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      Map<String, String> templateVersions,
      String carrier) {
    Optional<Long> carrierId =
        switch (request.mode()) {
          case COMMIT ->
              adapter.postCommitReview(
                  new PostCommitReviewCommand(
                      request.repoOwner(), request.repoName(), request.headSha(), summaryBody));
          case ISSUE ->
              adapter.postIssueReview(
                  new PostIssueCommand(
                      request.repoOwner(),
                      request.repoName(),
                      issueTitle(request),
                      summaryBody,
                      List.of("cra-nightly")));
          case PULL_REQUEST -> throw new IllegalStateException("不可达：PR 形态走 postReview");
        };
    trace.emit(
        "publish",
        Map.of(
            "mode",
            request.mode().name().toLowerCase(java.util.Locale.ROOT),
            "carrier",
            carrier,
            "comments",
            carrierId.isPresent() ? 1 : 0,
            "skipped_duplicate",
            skippedDuplicate,
            "suppressed_by_truncation",
            suppressedByTruncation,
            "done_marker",
            carrierId.isPresent()));
    if (carrierId.isEmpty()) {
      // 载体未返回 id = 无法确认完整发布：不登记账本，重跑重新发布（02§4.1）
      degradations.add("carrier-publish-no-id");
      return 0;
    }
    recordCarrierToLedger(
        request, runId, ordered, carrierId.get(), skippedDuplicate, degraded, templateVersions);
    return ordered.size();
  }

  private static String issueTitle(ReviewRequest request) {
    return "[cra-nightly] PR #"
        + request.prNumber()
        + " "
        + ReviewBodyRenderer.shortSha(request.headSha())
        + " 存量评审";
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
        request.key(),
        new LedgerRunMarker(
            runId, request.headSha(), status, ordered.size(), skippedDuplicate, summaryCommentId));
    // T2.11：模板版本随 run 落库（05§1.5 prompt_template_version；SQLite 形态覆写，GitHub 形态 default 无操作）
    ledger.recordTemplateVersions(request.key(), runId, templateVersions);
    Map<String, Long> commentIds =
        result.items().stream()
            .filter(i -> i.commentId() != null)
            .collect(
                Collectors.toMap(
                    ReviewItemResult::findingId, ReviewItemResult::commentId, (a, b) -> a));
    for (FindingAggregator.Scored s : ordered) {
      ledger.recordPublishedFinding(
          request.key(),
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
   * 无 PR 载体登记（M4c/M4d）：载体为单条原子评论，finding 级标记不存在（05§1.3 一条评论只承载一个末位标记）， 故只登记 run 完成标记与模板版本；finding
   * commentId 恒为 0。GitHub 载体账本为无操作（标记已内嵌正文）。
   */
  private void recordCarrierToLedger(
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      long carrierId,
      int skippedDuplicate,
      boolean degraded,
      Map<String, String> templateVersions) {
    String status = degraded ? LedgerRunMarker.STATUS_DEGRADED : LedgerRunMarker.STATUS_DONE;
    ledger.recordRunCompletion(
        request.key(),
        new LedgerRunMarker(
            runId, request.headSha(), status, ordered.size(), skippedDuplicate, carrierId));
    ledger.recordTemplateVersions(request.key(), runId, templateVersions);
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
    for (LedgerRunMarker old : ledger.completedRuns(request.key())) {
      if (old.commitSha().equalsIgnoreCase(request.headSha()) || old.commentId() <= 0) {
        continue;
      }
      String body =
          ledger.comment(request.key(), old.commentId()).map(LedgerComment::body).orElse(null);
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

  /** inline 候选里是否存在会破坏账本标记的路径（Summary 候选不受影响）。 */
  static boolean hasMarkerUnsafeInline(List<FindingAggregator.Scored> ordered) {
    return ordered.stream()
        .anyMatch(
            s ->
                s.anchor() == AnchorStatus.INLINE
                    && !ReviewBodyRenderer.markerSafePath(s.finding().file()));
  }
}
