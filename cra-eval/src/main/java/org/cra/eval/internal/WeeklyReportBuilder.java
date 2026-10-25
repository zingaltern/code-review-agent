package org.cra.eval.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PullRequestRef;
import org.cra.common.text.Texts;
import org.cra.eval.api.EvalReport;
import org.cra.eval.api.WeeklyReport;

/**
 * 周报聚合（06§1.1 核心公式；纯函数，无 I/O）：四类处置 → 发布精确率/误报率，拒真池 → 拒真率， trace → 漏斗，账本 run 完成标记 → 零发布率与满意度，最后做
 * T3.6 门槛评估。
 *
 * <p>样本量门槛是硬性如实性要求：处置主口径分母 <30 或拒真池 <20 时只出数值，判定字段置 null/false 并在文字结论 写明"样本不足，不下结论"（06§1.1 v1.2）。
 */
final class WeeklyReportBuilder {

  static final int DISPOSITION_SAMPLE_GATE = 30;
  static final int REFUSAL_SAMPLE_GATE = 20;
  static final double PRECISION_TARGET = 0.80;
  static final double FALSE_POSITIVE_LIMIT = 0.15;
  static final double REFUSAL_OVERTURN_TRIGGER = 0.10;
  private static final Duration IGNORED_AFTER = Duration.ofDays(7);

  private WeeklyReportBuilder() {}

  /**
   * 构建周报。
   *
   * @param window 取数窗口
   * @param now 报告生成时间
   * @param sinceLabel 窗口标签（如 7d）
   * @param windowStart 窗口起点
   * @param previous 历史报告（按 window_end 升序由本方法排序；可空）
   * @return 周报
   */
  static WeeklyReport build(
      WindowData.Window window,
      Instant now,
      String sinceLabel,
      Instant windowStart,
      List<WeeklyReport> previous) {
    List<String> notes = new ArrayList<>(window.notes());
    List<WeeklyReport.FindingRow> rows = new ArrayList<>();
    int fixed = 0;
    int wrong = 0;
    int intentional = 0;
    int ignored = 0;
    int pending = 0;
    for (WindowData.PrWindow pr : window.prs()) {
      Map<Long, LedgerComment> commentsById = new HashMap<>();
      pr.ledger().comments().forEach(c -> commentsById.put(c.id(), c));
      for (LedgerFindingMarker marker : pr.ledger().findingMarkers()) {
        Classification cls = classify(pr, marker, commentsById, now);
        switch (cls.disposition()) {
          case "fixed" -> fixed++;
          case "wrong" -> wrong++;
          case "intentional" -> intentional++;
          case "ignored" -> ignored++;
          default -> pending++;
        }
        rows.add(cls.row());
      }
    }
    int explicit = fixed + wrong + intentional;
    int mainDenominator = explicit + ignored;
    WeeklyReport.Disposition disposition =
        new WeeklyReport.Disposition(
            fixed,
            wrong,
            intentional,
            ignored,
            pending,
            explicit,
            mainDenominator,
            proportion(fixed + intentional, mainDenominator),
            proportion(wrong, mainDenominator),
            proportion(fixed + intentional, explicit),
            proportion(wrong, explicit),
            mainDenominator >= DISPOSITION_SAMPLE_GATE,
            DISPOSITION_SAMPLE_GATE,
            PRECISION_TARGET,
            FALSE_POSITIVE_LIMIT,
            mainDenominator >= DISPOSITION_SAMPLE_GATE
                ? (double) (fixed + intentional) / mainDenominator >= PRECISION_TARGET
                : null,
            mainDenominator >= DISPOSITION_SAMPLE_GATE
                ? (double) wrong / mainDenominator <= FALSE_POSITIVE_LIMIT
                : null);

    List<WindowData.RunFacts> runs = window.runs().stream().filter(r -> !r.dryRun()).toList();
    WeeklyReport.Refusal refusal = refusal(runs, window.badcases(), notes);
    WeeklyReport.Funnel funnel = funnel(runs, window.prs(), notes);
    WeeklyReport.ZeroPublish zeroPublish = zeroPublish(window.prs(), previous);
    WeeklyReport.Satisfaction satisfaction = satisfaction(window.prs());
    WeeklyReport.DualVerifierGate gate = gate(refusal, disposition);

    addStandingNotes(notes, window.source(), runs.size(), window.prs().size(), pending);
    return new WeeklyReport(
        window.repo().owner() + "/" + window.repo().repo(),
        sinceLabel,
        windowStart.toString(),
        now.toString(),
        now.toString(),
        window.source(),
        window.prs().size(),
        (int) window.prs().stream().filter(p -> p.pull().merged()).count(),
        (int)
            window.prs().stream()
                .mapToLong(
                    p -> p.ledger().runMarkers().stream().filter(m -> m.isCompletion()).count())
                .sum(),
        disposition,
        refusal,
        funnel,
        zeroPublish,
        satisfaction,
        gate,
        rows,
        notes);
  }

  // ---------- 处置分类 ----------

  private static Classification classify(
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
      note = "compare " + abbreviate(marker.commitSha()) + "..head 涉及锚定行（fixed 自动初判，可人工复核）";
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
  private record Classification(String disposition, WeeklyReport.FindingRow row) {}

  // ---------- 拒真率 ----------

  private static WeeklyReport.Refusal refusal(
      List<WindowData.RunFacts> runs, List<WindowData.BadcaseFacts> badcases, List<String> notes) {
    int refutedTotal = 0;
    int judgeUnavailable = 0;
    int judgeEvaluated = 0;
    int judgeOverturned = 0;
    Map<String, Boolean> pool = new LinkedHashMap<>();
    for (WindowData.RunFacts run : runs) {
      refutedTotal += run.refutedTotal();
      judgeUnavailable += run.judgeUnavailable();
      for (WindowData.RefusalSample sample : run.judgedRefusals()) {
        judgeEvaluated++;
        if (sample.overturned()) {
          judgeOverturned++;
        }
        pool.put(sample.findingId(), sample.overturned());
      }
    }
    int postHoc = 0;
    int postHocUnverified = 0;
    for (WindowData.BadcaseFacts badcase : badcases) {
      if (!"missed".equals(badcase.label()) || badcase.findingId().isBlank()) {
        continue;
      }
      if ("REFUTED".equalsIgnoreCase(badcase.verifierVerdict())) {
        postHoc++;
        pool.put(badcase.findingId(), true);
      } else if (badcase.verifierVerdict().isBlank()) {
        postHocUnverified++;
      }
    }
    if (postHocUnverified > 0) {
      notes.add(
          "有 "
              + postHocUnverified
              + " 条 missed badcase 未记录原验证判定（brief/trace 无 verifier_verdict），不进拒真池");
    }
    int overturned = (int) pool.values().stream().filter(Boolean::booleanValue).count();
    int poolSize = pool.size();
    Double rate = poolSize == 0 ? null : (double) overturned / poolSize;
    return new WeeklyReport.Refusal(
        refutedTotal,
        judgeEvaluated,
        judgeOverturned,
        judgeUnavailable,
        postHoc,
        poolSize,
        rate,
        poolSize >= REFUSAL_SAMPLE_GATE,
        REFUSAL_SAMPLE_GATE);
  }

  // ---------- 漏斗 ----------

  private static WeeklyReport.Funnel funnel(
      List<WindowData.RunFacts> runs, List<WindowData.PrWindow> prs, List<String> notes) {
    boolean candidateData = runs.stream().anyMatch(WindowData.RunFacts::promotionData);
    if (candidateData) {
      int candidates = runs.stream().mapToInt(WindowData.RunFacts::candidates).sum();
      int confirmed = runs.stream().mapToInt(WindowData.RunFacts::confirmed).sum();
      int published = runs.stream().mapToInt(WindowData.RunFacts::published).sum();
      int suppressed = runs.stream().mapToInt(WindowData.RunFacts::suppressedByTruncation).sum();
      int truncatedRuns = (int) runs.stream().filter(r -> r.suppressedByTruncation() > 0).count();
      return new WeeklyReport.Funnel(
          true,
          "trace（" + runs.size() + " run）",
          runs.size(),
          candidates,
          confirmed,
          published,
          suppressed,
          truncatedRuns,
          rate(confirmed, candidates),
          rate(published, confirmed),
          rate(suppressed, confirmed));
    }
    int published = 0;
    int suppressed = 0;
    int runMarkers = 0;
    for (WindowData.PrWindow pr : prs) {
      for (LedgerRunMarker marker : pr.ledger().runMarkers()) {
        runMarkers++;
        published += marker.findings();
        suppressed += marker.suppressed();
      }
    }
    notes.add(
        "未提供 run trace：candidates→confirmed 不可得（填 -1）；published/suppressed 来自账本 run 完成标记（"
            + runMarkers
            + " 个）");
    return new WeeklyReport.Funnel(
        false, "ledger run 完成标记", runMarkers, -1, -1, published, suppressed, -1, null, null, null);
  }

  // ---------- 零发布率与趋势 ----------

  private static WeeklyReport.ZeroPublish zeroPublish(
      List<WindowData.PrWindow> prs, List<WeeklyReport> previous) {
    int runs = 0;
    int zero = 0;
    for (WindowData.PrWindow pr : prs) {
      for (LedgerRunMarker marker : pr.ledger().runMarkers()) {
        runs++;
        if (marker.findings() == 0) {
          zero++;
        }
      }
    }
    Double ratio = runs == 0 ? null : (double) zero / runs;
    List<WeeklyReport> history =
        previous.stream()
            .sorted(Comparator.comparing(r -> r.windowEnd() == null ? "" : r.windowEnd()))
            .toList();
    List<Double> series = new ArrayList<>();
    for (WeeklyReport report : history) {
      if (report.zeroPublish() != null && report.zeroPublish().ratio() != null) {
        series.add(report.zeroPublish().ratio());
      }
    }
    Double previousRatio = series.isEmpty() ? null : series.get(series.size() - 1);
    if (ratio != null) {
      series.add(ratio);
    }
    String trend;
    boolean consecutiveRise = false;
    String note;
    if (ratio == null) {
      trend = "insufficient";
      note = "窗口内无 run 完成标记，零发布率不可判定";
    } else if (series.size() >= 3) {
      int n = series.size();
      consecutiveRise =
          series.get(n - 3) < series.get(n - 2) && series.get(n - 2) < series.get(n - 1);
      if (consecutiveRise) {
        trend = "rising";
        note = "连续两周上行 → 触发复盘（06§1.1 趋势护栏）";
      } else if (series.get(n - 1) > series.get(n - 2)) {
        trend = "rising";
        note = "较上期上行，但未构成连续两周（需 ≥3 期数据）";
      } else if (series.get(n - 1) < series.get(n - 2)) {
        trend = "falling";
        note = "较上期下行";
      } else {
        trend = "flat";
        note = "与上期持平";
      }
    } else {
      trend = "insufficient";
      note = "历史报告不足（需本期 + 至少两期历史才判定连续两周趋势）";
    }
    return new WeeklyReport.ZeroPublish(
        runs, zero, ratio, previousRatio, series, consecutiveRise, trend, note);
  }

  // ---------- run 满意度 ----------

  private static WeeklyReport.Satisfaction satisfaction(List<WindowData.PrWindow> prs) {
    int up = 0;
    int down = 0;
    Set<Long> reactedComments = new LinkedHashSet<>();
    for (WindowData.PrWindow pr : prs) {
      Set<Long> summaryIds = new LinkedHashSet<>();
      for (LedgerRunMarker marker : pr.ledger().runMarkers()) {
        summaryIds.add(marker.commentId());
      }
      for (DispositionSignal signal : pr.signals()) {
        if (!DispositionSignal.KIND_REACTION.equals(signal.kind())
            || !summaryIds.contains(signal.commentId())) {
          continue;
        }
        if (DispositionSignal.REACTION_UP.equals(signal.detail())) {
          up++;
          reactedComments.add(signal.commentId());
        } else if (DispositionSignal.REACTION_DOWN.equals(signal.detail())) {
          down++;
          reactedComments.add(signal.commentId());
        }
      }
    }
    return new WeeklyReport.Satisfaction(up, down, reactedComments.size());
  }

  // ---------- T3.6 门槛评估 ----------

  private static WeeklyReport.DualVerifierGate gate(
      WeeklyReport.Refusal refusal, WeeklyReport.Disposition disposition) {
    boolean refusalSampleOk = refusal.samplesSufficient();
    boolean refusalMet =
        refusalSampleOk
            && refusal.overturnRate() != null
            && refusal.overturnRate() >= REFUSAL_OVERTURN_TRIGGER;
    String refusalVerdict;
    if (!refusalSampleOk) {
      refusalVerdict = "拒真池 " + refusal.pool() + " < " + REFUSAL_SAMPLE_GATE + "，样本不足，不下结论";
    } else if (refusal.overturnRate() == null) {
      refusalVerdict = "拒真池无可用判定，不下结论";
    } else {
      refusalVerdict =
          (refusalMet ? "推翻率 " : "推翻率 ")
              + percent(refusal.overturnRate())
              + (refusalMet ? " ≥ 10% → 条件满足" : " < 10% → 条件不满足");
    }
    WeeklyReport.GateCondition refusalCondition =
        new WeeklyReport.GateCondition(
            refusalSampleOk,
            refusal.pool(),
            REFUSAL_SAMPLE_GATE,
            refusal.overturnRate(),
            REFUSAL_OVERTURN_TRIGGER,
            "gte",
            refusalMet,
            refusalVerdict);

    boolean dispositionSampleOk = disposition.samplesSufficient();
    Double precision = disposition.precision() == null ? null : disposition.precision().rate();
    boolean dispositionMet =
        dispositionSampleOk && precision != null && precision < PRECISION_TARGET;
    String dispositionVerdict;
    if (!dispositionSampleOk) {
      dispositionVerdict =
          "处置主口径样本 "
              + disposition.samplesIncludingIgnored()
              + " < "
              + DISPOSITION_SAMPLE_GATE
              + "，样本不足，不下结论";
    } else if (precision == null) {
      dispositionVerdict = "处置样本无可用精确率，不下结论";
    } else {
      dispositionVerdict =
          "发布精确率 " + percent(precision) + (dispositionMet ? " < 80% → 条件满足" : " ≥ 80% → 条件不满足");
    }
    WeeklyReport.GateCondition dispositionCondition =
        new WeeklyReport.GateCondition(
            dispositionSampleOk,
            disposition.samplesIncludingIgnored(),
            DISPOSITION_SAMPLE_GATE,
            precision,
            PRECISION_TARGET,
            "lt",
            dispositionMet,
            dispositionVerdict);

    boolean triggered = refusalMet || dispositionMet;
    List<String> reasons = new ArrayList<>();
    if (triggered) {
      reasons.add("满足开启条件：需先实施 require-distinct-model-family 硬校验 + 语义去重（03§4.3/05§5）");
      reasons.add("开启前后 A/B 数据入档（07 T3.6 验收 3）");
    } else {
      reasons.add("结论：不开启双异构验证者，继续单验证者 + important/security 级 REFUTED 抽查");
      reasons.add("不得为完成 T3.6 放松门槛；数据变化后重跑本评估");
    }
    return new WeeklyReport.DualVerifierGate(
        triggered,
        triggered ? "满足开启条件（需实施 dual-vote 硬校验 + 语义去重）" : "不开启（继续单验证者 + 抽查）",
        refusalCondition,
        dispositionCondition,
        reasons);
  }

  // ---------- 通用 ----------

  private static void addStandingNotes(
      List<String> notes, String source, int runs, int prs, int pending) {
    notes.add("账本数据源：" + source + "（PR " + prs + " 个；trace run " + runs + " 个）");
    notes.add("处置分类只覆盖 inline 评论（账本标记必需）：Summary 降级的 finding 无独立评论，不参与处置分母");
    notes.add("fixed 为自动初判（后续 commit 触及锚定行），不等同人工确认修复；wrong/intentional 来自 reply 标签");
    notes.add("ignored = 合并超 7 天且锚定行未改、无 reply 标签；主口径分母含 ignored 噪声（06§1.1 口径声明），" + "附口径仅含有向处置");
    if (pending > 0) {
      notes.add("pending " + pending + " 条（未合并/合并未满 7 天/compare 不可得），不计入分母");
    }
    notes.add("resolve 状态为 GraphQL-only，REST v3 下不采（05§2.3）——本报告不含 resolve 信号");
  }

  private static EvalReport.ProportionMetric proportion(long numerator, long denominator) {
    if (denominator <= 0) {
      return null;
    }
    double[] interval = WilsonInterval.wilson95(numerator, denominator);
    return new EvalReport.ProportionMetric(
        numerator, denominator, (double) numerator / denominator, interval[0], interval[1]);
  }

  private static Double rate(int numerator, int denominator) {
    return denominator <= 0 ? null : (double) numerator / denominator;
  }

  private static String percent(Double value) {
    return value == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100);
  }

  private static String abbreviate(String sha) {
    return Texts.shortSha(sha, 8, "?");
  }
}
