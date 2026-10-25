package org.cra.eval.internal;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.cra.eval.api.EvalReport;

/**
 * 报告输出（文本 → stdout + JSON 文件）。09§5 禁 System.out 针对的是**日志逃逸**；评测报告是 CLI 产品输出（05§3 {@code cra-eval}
 * 契约），经显式包装 {@link FileDescriptor#out} 输出，与 SLF4J 日志通道分离。
 */
public final class ReportWriter {

  private static final PrintStream OUT =
      new PrintStream(
          (OutputStream) new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

  private ReportWriter() {}

  /**
   * 渲染文本报告到 stdout。
   *
   * @param report 报告
   */
  public static void renderText(EvalReport report) {
    OUT.println("== cra-eval run 聚合报告 ==");
    OUT.println("数据集: " + report.datasetPath());
    OUT.println(
        "被测 CLI: "
            + report.cliJar()
            + "  深度: "
            + report.depth()
            + "  k: "
            + report.k()
            + "  llm_configured: "
            + report.llmConfigured());
    if (report.commit() != null) {
      OUT.println("代码提交: " + report.commit());
    }
    if (report.blockedNote() != null && !report.blockedNote().isBlank()) {
      OUT.println("⚠ " + report.blockedNote());
    }
    if (report.gate() != null) {
      renderGate(report.gate());
    }
    for (EvalReport.SetStat s : report.sets()) {
      OUT.println();
      OUT.println("-- " + setLabel(s));
      OUT.println(
          "样本 "
              + s.samples()
              + "  观测 "
              + s.observations()
              + "（完成 "
              + s.completed()
              + " / 失败 "
              + s.failed()
              + "）");
      if (s.detection() != null && Double.isNaN(s.detection().rate())) {
        OUT.println("检出率: 无观测");
      } else if (s.detection() != null) {
        EvalReport.ProportionMetric d = s.detection();
        OUT.printf(
            Locale.ROOT,
            "检出率 %d/%d = %.3f  Wilson95 [%.4f, %.4f]%n",
            d.numerator(),
            d.denominator(),
            d.rate(),
            d.wilsonLow(),
            d.wilsonHigh());
      }
      if (s.rejection() != null && !Double.isNaN(s.rejection().rate())) {
        EvalReport.ProportionMetric r = s.rejection();
        OUT.printf(
            Locale.ROOT,
            "拒绝率 %d/%d = %.3f  Wilson95 [%.4f, %.4f]%n",
            r.numerator(),
            r.denominator(),
            r.rate(),
            r.wilsonLow(),
            r.wilsonHigh());
      }
      if (s.meanFalsePositives() != null) {
        OUT.printf(
            Locale.ROOT, "每 run 平均误报 %.2f 条（分母 %d 个完成观测）%n", s.meanFalsePositives(), s.completed());
      }
      if (s.runMetrics() != null && s.runMetrics().observationsWithStats() > 0) {
        EvalReport.RunMetrics m = s.runMetrics();
        OUT.printf(
            Locale.ROOT,
            "run 统计（%d 个观测有 run_end 记录）: tokens_in sum=%d mean=%.0f  tokens_out sum=%d mean=%.0f"
                + "  latency mean=%.0fms%n",
            m.observationsWithStats(),
            m.tokensInSum(),
            m.tokensInMean(),
            m.tokensOutSum(),
            m.tokensOutMean(),
            m.latencyMsMean());
      }
      if (s.invariantViolations() != null) {
        OUT.println(
            "注入负向不变量违例 "
                + s.invariantViolations()
                + "（零容忍: "
                + (s.invariantViolations() > 0 ? "红" : "绿")
                + "）");
      }
      if (s.zeroPublish() != null) {
        EvalReport.ZeroPublish z = s.zeroPublish();
        OUT.printf(
            Locale.ROOT,
            "零发布护栏 %d/%d = %.3f（限 %.3f）%s%n",
            z.zeroPublishObservations(),
            z.completedDenominator(),
            z.ratio(),
            z.limit(),
            z.red() ? "红" : "绿");
      }
      if (!s.samplesSufficient()) {
        OUT.println(
            "（样本量/完成度不足结论门槛 " + AggregateEngine.MIN_OBSERVATIONS_FOR_CONCLUSION + "：只出数值不下结论）");
      }
      OUT.println("逐样本:");
      for (EvalReport.CaseRow c : s.cases()) {
        OUT.println(
            "  "
                + c.caseId()
                + "#"
                + c.runIndex()
                + "  status="
                + c.runStatus()
                + "  published="
                + c.publishedFindings()
                + "  matched="
                + c.matchedExpected()
                + "/"
                + c.totalExpected()
                + (c.baitHits() > 0 ? "  bait命中=" + c.baitHits() : "")
                + (c.invariantViolations() > 0 ? "  违例=" + c.invariantViolations() : "")
                + (c.note().isEmpty() ? "" : "  note=" + c.note()));
      }
    }
    OUT.println();
    OUT.println(
        "（口径：观测 = (样本, run)；检出 = ≥1 expected 严格命中（file + 行 ±5 + 类别 + 全部关键词）；详见 dataset/README.md）");
  }

  private static String setLabel(EvalReport.SetStat s) {
    if (s.subtype() == null) {
      return "集合 " + s.set();
    }
    return "集合 " + s.set() + "/" + s.subtype();
  }

  static void renderGate(EvalReport.Gate gate) {
    OUT.println();
    OUT.println("门禁: " + (gate.passed() ? "绿" : "红") + "（tier=" + gate.tier() + "，06§1.2）");
    for (EvalReport.GateCheck c : gate.checks()) {
      OUT.println("  [" + c.status() + "] " + c.id() + " — " + c.detail());
    }
  }

  /**
   * 写 JSON 报告（--json 落盘，供 L2 报告哈希校验，04§2.4）。
   *
   * @param report 报告
   * @param outPath 输出路径
   */
  public static void writeJson(EvalReport report, Path outPath) {
    Json.write(outPath, report);
  }

  /**
   * 输出注记说明（验收运行后的人工核对入口）。
   *
   * @param lines 附加行
   */
  public static void printLines(List<String> lines) {
    for (String line : lines) {
      OUT.println(line);
    }
  }
}
