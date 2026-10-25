package org.cra.eval.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.PullRequestRef;
import org.cra.common.text.Texts;
import org.cra.eval.api.WeeklyReport;

/** 周报处置分类（06§1.1）：reply 标签优先 → fixed/ignored/pending 判定，产出逐 finding 行。 */
final class WeeklyReportClassifier {

  private static final Duration IGNORED_AFTER = Duration.ofDays(7);

  private WeeklyReportClassifier() {}

  static Classification classify(
      WindowData.PrWindow pr,
      LedgerFindingMarker marker,
      Map<Long, LedgerComment> commentsById,
      Instant now) {
    PullRequestRef pull = pr.pull();
    LedgerComment comment = commentsById.get(marker.commentId());
    Instant publishedAt = comment == null ? null : comment.createdAt();
    long ageDays =
        publishedAt == null || Instant.EPOCH.equals(publishedAt)
            ? -1
            : Duration.between(publishedAt, now).toDays();
    DispositionSignal tag = latestTag(pr.signals(), marker.commentId());
    WindowData.TouchFacts touch = pr.touchesByCommit().get(marker.commitSha());
    String disposition;
    String signal;
    String note;
    if (tag != null) {
      disposition = tag.detail();
      signal = "reply:" + tag.detail();
      note = tag.excerpt();
    } else if (pull.merged() && touch != null && touch.touches(marker.file(), marker.line())) {
      disposition = "fixed";
      signal = "line-changed";
      note =
          "compare "
              + Texts.shortSha(marker.commitSha(), 8, "?")
              + "..head 涉及锚定行（fixed 自动初判，可人工复核）";
    } else if (pull.merged() && touch != null && isPastIgnoredWindow(pull, now)) {
      disposition = "ignored";
      signal = "merged-unmodified";
      note = "合并超过 7 天且锚定行未改、无 reply 标签（噪声口径，分母含此样本）";
    } else if (pull.merged() && touch == null) {
      disposition = "pending";
      signal = "unknown-change";
      note = "后续 commit compare 不可得，无法判定行是否变更（不计入分母）";
    } else if (pull.merged()) {
      disposition = "pending";
      signal = "awaiting-window";
      note = "合并未满 7 天，ignored 判定时点未到";
    } else {
      disposition = "pending";
      signal = "pr-unmerged";
      note = "PR 未合并（06§1.1 合并事件回填的前提未满足）";
    }
    return new Classification(
        disposition,
        new WeeklyReport.FindingRow(
            pull.number(),
            marker.commentId(),
            marker.runId(),
            marker.findingId(),
            marker.file(),
            marker.line(),
            marker.category(),
            disposition,
            signal,
            publishedAt == null ? "" : publishedAt.toString(),
            ageDays,
            note));
  }

  private static boolean isPastIgnoredWindow(PullRequestRef pull, Instant now) {
    return pull.mergedAt() != null && !now.isBefore(pull.mergedAt().plus(IGNORED_AFTER));
  }

  private static DispositionSignal latestTag(List<DispositionSignal> signals, long commentId) {
    DispositionSignal best = null;
    for (DispositionSignal signal : signals) {
      if (!DispositionSignal.KIND_REPLY_TAG.equals(signal.kind())
          || signal.commentId() != commentId
          || tagPriority(signal.detail()) < 0) {
        continue;
      }
      if (best == null
          || signal.occurredAt().isAfter(best.occurredAt())
          || (signal.occurredAt().equals(best.occurredAt())
              && tagPriority(signal.detail()) < tagPriority(best.detail()))) {
        best = signal;
      }
    }
    return best;
  }

  private static int tagPriority(String tag) {
    return switch (tag == null ? "" : tag) {
      case DispositionSignal.TAG_WRONG -> 0;
      case DispositionSignal.TAG_INTENTIONAL -> 1;
      case DispositionSignal.TAG_FIXED -> 2;
      default -> -1;
    };
  }

  /** 分类中间结果。 */
  record Classification(String disposition, WeeklyReport.FindingRow row) {}
}
