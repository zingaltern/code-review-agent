package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.eval.api.EvalReport;
import org.cra.eval.api.WeeklyReport;

/** 周报输出（文本 = CLI 产品输出，JSON = 趋势基线与审计数据面；06§1.1）。 */
final class WeeklyReportWriter {

  private WeeklyReportWriter() {}

  /**
   * 渲染文本报告行（不直接写 stdout，便于单测断言）。
   *
   * @param report 周报
   * @return 文本行
   */
  static List<String> renderText(WeeklyReport report) {
    List<String> lines = new ArrayList<>();
    appendHeader(lines, report);
    appendDisposition(lines, report.disposition());
    appendRefusal(lines, report.refusal());
    appendFunnel(lines, report.funnel());
    appendZeroPublish(lines, report.zeroPublish());
    appendSatisfaction(lines, report.satisfaction());
    appendDualVerifierGate(lines, report.dualVerifierGate());
    lines.add("");
    lines.add("-- 口径与不可得声明");
    for (String note : report.notes()) {
      lines.add("- " + note);
    }
    return lines;
  }

  /**
   * 报告头（仓库/窗口/数据源/PR/run 完成标记）。
   *
   * @param lines 输出
   * @param report 周报
   */
  private static void appendHeader(List<String> lines, WeeklyReport report) {
    lines.add("== cra-eval 周报（L1 在线噪声度量，06§1.1） ==");
    lines.add(
        "仓库: "
            + report.repo()
            + "  窗口: "
            + report.windowStart()
            + " ~ "
            + report.windowEnd()
            + "（since "
            + report.since()
            + "）  数据源: "
            + report.dataSource());
    lines.add(
        "PR: "
            + report.prs()
            + "（合并 "
            + report.mergedPrs()
            + "）  run 完成标记: "
            + report.runCompletions());
  }

  /**
   * 发布精确率 / 误报率段。
   *
   * @param lines 输出
   * @param d 处置统计
   */
  private static void appendDisposition(List<String> lines, WeeklyReport.Disposition d) {
    lines.add("");
    lines.add("-- 发布精确率 / 误报率（06§1.1）");
    lines.add(
        "处置分布: fixed="
            + d.fixed()
            + "  intentional="
            + d.intentional()
            + "  wrong="
            + d.wrong()
            + "  ignored="
            + d.ignored()
            + "  pending="
            + d.pending());
    lines.add(
        "主口径（分母 "
            + d.samplesIncludingIgnored()
            + " 含 ignored 噪声）: 发布精确率 "
            + metric(d.precision())
            + "；误报率 "
            + metric(d.falsePositive()));
    lines.add(
        "附口径（分母 "
            + d.explicitSamples()
            + " 仅 fixed/wrong/intentional）: 发布精确率 "
            + metric(d.precisionExplicitOnly())
            + "；误报率 "
            + metric(d.falsePositiveExplicitOnly()));
    if (!d.samplesSufficient()) {
      lines.add(
          "⚠ 样本不足，不下结论：主口径 "
              + d.samplesIncludingIgnored()
              + " < 门槛 "
              + d.sampleGate()
              + "（目标精确率 ≥"
              + percent(d.precisionTarget())
              + "、误报率 ≤"
              + percent(d.falsePositiveLimit())
              + "）");
    } else {
      lines.add(
          "判定: 精确率 ≥"
              + percent(d.precisionTarget())
              + " → "
              + (Boolean.TRUE.equals(d.meetsPrecisionTarget()) ? "达标" : "未达标")
              + "；误报率 ≤"
              + percent(d.falsePositiveLimit())
              + " → "
              + (Boolean.TRUE.equals(d.meetsFalsePositiveLimit()) ? "达标" : "未达标"));
    }
  }

  /**
   * 拒真率段。
   *
   * @param lines 输出
   * @param r 拒真统计
   */
  private static void appendRefusal(List<String> lines, WeeklyReport.Refusal r) {
    lines.add("");
    lines.add("-- 拒真率（REFUTED 被 Judge/事后推翻）");
    lines.add(
        "REFUTED 总数: "
            + r.refutedTotal()
            + "  Judge 复核: "
            + r.judgeEvaluated()
            + "（推翻 "
            + r.judgeOverturned()
            + "；不可得 "
            + r.judgeUnavailable()
            + "）  事后 badcase 推翻: "
            + r.postHocOverturned());
    lines.add(
        "拒真评估池: "
            + r.pool()
            + "（门槛 ≥"
            + r.sampleGate()
            + "）  推翻率: "
            + (r.overturnRate() == null ? "n/a" : percent(r.overturnRate())));
    String overturnText = r.overturnRate() == null ? "n/a" : percent(r.overturnRate());
    lines.add(
        r.samplesSufficient()
            ? "判定: 推翻率 " + overturnText
            : "⚠ 样本不足，不下结论（拒真池 " + r.pool() + " < " + r.sampleGate() + "）");
  }

  /**
   * 候选→确认→发布漏斗段。
   *
   * @param lines 输出
   * @param f 漏斗
   */
  private static void appendFunnel(List<String> lines, WeeklyReport.Funnel f) {
    lines.add("");
    lines.add("-- 候选→确认→发布漏斗（06§1.1/§1.3）");
    if (f.candidateDataAvailable()) {
      lines.add(
          "candidates "
              + f.candidates()
              + " → confirmed "
              + f.confirmed()
              + " → published "
              + f.published()
              + "（run "
              + f.runs()
              + " 个）");
      lines.add(
          "转化率: confirmed/candidates="
              + rate(f.confirmedRate())
              + "  published/confirmed="
              + rate(f.publishRate()));
      lines.add(
          "suppressed_by_truncation="
              + f.suppressedByTruncation()
              + "（截断 run "
              + f.truncatedRuns()
              + " 个，占 "
              + rate(f.truncatedRate())
              + "）——梯度沉默护栏");
      lines.add("数据源: " + f.source());
    } else {
      lines.add(
          "candidates→confirmed 不可得（未提供 run trace）；账本可见: published="
              + f.published()
              + "  suppressed_by_truncation="
              + f.suppressedByTruncation()
              + "（run 完成标记 "
              + f.runs()
              + " 个）");
    }
  }

  /**
   * 零发布率趋势段。
   *
   * @param lines 输出
   * @param z 零发布统计
   */
  private static void appendZeroPublish(List<String> lines, WeeklyReport.ZeroPublish z) {
    lines.add("");
    lines.add("-- 零发布率趋势（06§1.1 趋势护栏）");
    lines.add(
        "零发布率: "
            + z.zeroRuns()
            + "/"
            + z.runs()
            + " = "
            + (z.ratio() == null ? "n/a（无 run 完成标记）" : percent(z.ratio()))
            + "  上期: "
            + (z.previousRatio() == null ? "n/a" : percent(z.previousRatio()))
            + "  趋势: "
            + z.trend());
    lines.add("  " + z.note());
  }

  /**
   * run 满意度段。
   *
   * @param lines 输出
   * @param s 满意度
   */
  private static void appendSatisfaction(List<String> lines, WeeklyReport.Satisfaction s) {
    lines.add("");
    lines.add("-- run 满意度（Summary reaction，不参与单 finding 分类）");
    lines.add(
        "👍 " + s.up() + " / 👎 " + s.down() + "（有 reaction 的 run " + s.runsWithReaction() + " 个）");
  }

  /**
   * 双异构验证者门槛评估段。
   *
   * @param lines 输出
   * @param g 门槛评估
   */
  private static void appendDualVerifierGate(List<String> lines, WeeklyReport.DualVerifierGate g) {
    lines.add("");
    lines.add("-- 双异构验证者门槛评估（T3.6：03§4.3 / 07）");
    lines.add("条件 A（拒真池 ≥20 且推翻率 ≥10%）：" + g.refusalCondition().verdict());
    lines.add("条件 B（处置样本 ≥30 且精确率 <80%）：" + g.dispositionCondition().verdict());
    lines.add("结论：" + g.decision() + (g.triggered() ? "" : "（本里程碑未实施硬校验；不得为完成 T3.6 放松门槛）"));
    for (String reason : g.reasons()) {
      lines.add("  - " + reason);
    }
  }

  /**
   * 写 JSON 报告。
   *
   * @param report 周报
   * @param out 输出路径
   */
  static void writeJson(WeeklyReport report, Path out) {
    Json.write(out, report);
  }

  private static String metric(EvalReport.ProportionMetric metric) {
    if (metric == null) {
      return "n/a（分母为 0）";
    }
    return String.format(
        Locale.ROOT,
        "%d/%d = %.3f  Wilson95 [%.4f, %.4f]",
        metric.numerator(),
        metric.denominator(),
        metric.rate(),
        metric.wilsonLow(),
        metric.wilsonHigh());
  }

  private static String percent(double value) {
    return String.format(Locale.ROOT, "%.1f%%", value * 100);
  }

  private static String rate(Double value) {
    return value == null ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
  }
}
