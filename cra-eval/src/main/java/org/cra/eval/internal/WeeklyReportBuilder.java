package org.cra.eval.internal;

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
import java.util.stream.Collectors;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
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
        WeeklyReportClassifier.Classification cls =
            WeeklyReportClassifier.classify(pr, marker, commentsById, now);
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
    EvalReport.ProportionMetric precisionMetric =
        proportion((long) fixed + intentional, mainDenominator);
    EvalReport.ProportionMetric falsePositiveMetric = proportion(wrong, mainDenominator);
    WeeklyReport.Disposition disposition =
        new WeeklyReport.Disposition(
            fixed,
            wrong,
            intentional,
            ignored,
            pending,
            explicit,
            mainDenominator,
            precisionMetric,
            falsePositiveMetric,
            proportion((long) fixed + intentional, explicit),
            proportion(wrong, explicit),
            mainDenominator >= DISPOSITION_SAMPLE_GATE,
            DISPOSITION_SAMPLE_GATE,
            PRECISION_TARGET,
            FALSE_POSITIVE_LIMIT,
            // 门禁判定复用上方 proportion（除零防护在 proportion 内：分母 ≤0 置 null）；样本 <30 按口径不判定
            mainDenominator >= DISPOSITION_SAMPLE_GATE
                ? precisionMetric != null && precisionMetric.rate() >= PRECISION_TARGET
                : null,
            mainDenominator >= DISPOSITION_SAMPLE_GATE
                ? falsePositiveMetric != null && falsePositiveMetric.rate() <= FALSE_POSITIVE_LIMIT
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
    ZeroCounts counts = countZeroPublish(prs);
    Double ratio = counts.runs() == 0 ? null : (double) counts.zero() / counts.runs();
    List<WeeklyReport> history =
        previous.stream()
            .sorted(Comparator.comparing(r -> r.windowEnd() == null ? "" : r.windowEnd()))
            .toList();
    List<Double> series =
        history.stream()
            .filter(r -> r.zeroPublish() != null && r.zeroPublish().ratio() != null)
            .map(r -> r.zeroPublish().ratio())
            .collect(Collectors.toCollection(ArrayList::new));
    Double previousRatio = series.isEmpty() ? null : series.get(series.size() - 1);
    if (ratio != null) {
      series.add(ratio);
    }
    Trend trend = zeroPublishTrend(series, ratio);
    return new WeeklyReport.ZeroPublish(
        counts.runs(),
        counts.zero(),
        ratio,
        previousRatio,
        series,
        trend.consecutiveRise(),
        trend.trend(),
        trend.note());
  }

  /**
   * 统计窗口内 run 完成标记数与零发布标记数。
   *
   * @param prs 窗口 PR
   * @return 计数
   */
  private static ZeroCounts countZeroPublish(List<WindowData.PrWindow> prs) {
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
    return new ZeroCounts(runs, zero);
  }

  /**
   * 零发布率趋势判定（06§1.1 趋势护栏：连续两周上行触发复盘）。
   *
   * @param series 历史比率序列（末尾已含本期）
   * @param ratio 本期比率（null = 不可判定）
   * @return 趋势
   */
  private static Trend zeroPublishTrend(List<Double> series, Double ratio) {
    if (ratio == null) {
      return new Trend(false, "insufficient", "窗口内无 run 完成标记，零发布率不可判定");
    }
    if (series.size() < 3) {
      return new Trend(false, "insufficient", "历史报告不足（需本期 + 至少两期历史才判定连续两周趋势）");
    }
    int n = series.size();
    boolean consecutiveRise =
        series.get(n - 3) < series.get(n - 2) && series.get(n - 2) < series.get(n - 1);
    if (consecutiveRise) {
      return new Trend(true, "rising", "连续两周上行 → 触发复盘（06§1.1 趋势护栏）");
    }
    if (series.get(n - 1) > series.get(n - 2)) {
      return new Trend(false, "rising", "较上期上行，但未构成连续两周（需 ≥3 期数据）");
    }
    if (series.get(n - 1) < series.get(n - 2)) {
      return new Trend(false, "falling", "较上期下行");
    }
    return new Trend(false, "flat", "与上期持平");
  }

  /**
   * 零发布计数。
   *
   * @param runs run 完成标记数
   * @param zero 零发布标记数
   */
  private record ZeroCounts(int runs, int zero) {}

  /**
   * 零发布趋势。
   *
   * @param consecutiveRise 是否连续两周上行
   * @param trend 趋势标签
   * @param note 说明
   */
  private record Trend(boolean consecutiveRise, String trend, String note) {}

  // ---------- run 满意度 ----------

  private static WeeklyReport.Satisfaction satisfaction(List<WindowData.PrWindow> prs) {
    int up = 0;
    int down = 0;
    Set<Long> reactedComments = new LinkedHashSet<>();
    for (WindowData.PrWindow pr : prs) {
      Set<Long> summaryIds =
          pr.ledger().runMarkers().stream()
              .map(LedgerRunMarker::commentId)
              .collect(Collectors.toCollection(LinkedHashSet::new));
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
    RefusalGate refusalGate = refusalGate(refusal);
    DispositionGate dispositionGate = dispositionGate(disposition);
    boolean triggered = refusalGate.met() || dispositionGate.met();
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
        refusalGate.condition(),
        dispositionGate.condition(),
        reasons);
  }

  /**
   * 拒真池开启条件（样本充足且推翻率 ≥ 阈值）。
   *
   * @param refusal 拒真池统计
   * @return 条件与判定
   */
  private static RefusalGate refusalGate(WeeklyReport.Refusal refusal) {
    boolean sampleOk = refusal.samplesSufficient();
    boolean met =
        sampleOk
            && refusal.overturnRate() != null
            && refusal.overturnRate() >= REFUSAL_OVERTURN_TRIGGER;
    String verdict;
    if (!sampleOk) {
      verdict = "拒真池 " + refusal.pool() + " < " + REFUSAL_SAMPLE_GATE + "，样本不足，不下结论";
    } else if (refusal.overturnRate() == null) {
      verdict = "拒真池无可用判定，不下结论";
    } else {
      verdict =
          "推翻率 " + percent(refusal.overturnRate()) + (met ? " ≥ 10% → 条件满足" : " < 10% → 条件不满足");
    }
    return new RefusalGate(
        new WeeklyReport.GateCondition(
            sampleOk,
            refusal.pool(),
            REFUSAL_SAMPLE_GATE,
            refusal.overturnRate(),
            REFUSAL_OVERTURN_TRIGGER,
            "gte",
            met,
            verdict),
        met);
  }

  /**
   * 处置精确率开启条件（样本充足且精确率 &lt; 阈值）。
   *
   * @param disposition 处置统计
   * @return 条件与判定
   */
  private static DispositionGate dispositionGate(WeeklyReport.Disposition disposition) {
    boolean sampleOk = disposition.samplesSufficient();
    Double precision = disposition.precision() == null ? null : disposition.precision().rate();
    boolean met = sampleOk && precision != null && precision < PRECISION_TARGET;
    String verdict;
    if (!sampleOk) {
      verdict =
          "处置主口径样本 "
              + disposition.samplesIncludingIgnored()
              + " < "
              + DISPOSITION_SAMPLE_GATE
              + "，样本不足，不下结论";
    } else if (precision == null) {
      verdict = "处置样本无可用精确率，不下结论";
    } else {
      verdict = "发布精确率 " + percent(precision) + (met ? " < 80% → 条件满足" : " ≥ 80% → 条件不满足");
    }
    return new DispositionGate(
        new WeeklyReport.GateCondition(
            sampleOk,
            disposition.samplesIncludingIgnored(),
            DISPOSITION_SAMPLE_GATE,
            precision,
            PRECISION_TARGET,
            "lt",
            met,
            verdict),
        met);
  }

  /**
   * 拒真池门禁条件。
   *
   * @param condition 门禁条件
   * @param met 是否满足开启条件
   */
  private record RefusalGate(WeeklyReport.GateCondition condition, boolean met) {}

  /**
   * 处置精确率门禁条件。
   *
   * @param condition 门禁条件
   * @param met 是否满足开启条件
   */
  private record DispositionGate(WeeklyReport.GateCondition condition, boolean met) {}

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
}
