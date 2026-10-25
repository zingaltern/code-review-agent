package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.EvalCli;
import org.cra.eval.api.EvalReport;

/**
 * {@code cra-eval gate} 执行（T3.1：对已有报告离线重判；不重跑 LLM）。
 *
 * <p>用途：阈值/检查项变更后，用同一批观测重算门禁结论（评测重跑范围纪律——只重判、不整轮重跑）；也用于从 JSON 报告独立复核门禁结论。输入 = 聚合报告（`sets`
 * 内含全部逐观测数据），输出 = 新 gate 结论（可选落盘新报告与哈希）。
 *
 * <p>退出码：0 = 绿；5 = 红（与 `--fail-on-red` 的 run 一致，便于 CI/脚本复用）。
 */
public final class GateCommand {

  private GateCommand() {}

  /**
   * 执行离线重判。
   *
   * @param args 已解析参数
   * @return 退出码（0 = 绿；5 = 红）
   */
  public static int execute(EvalCliArgs.GateArgs args) {
    Path reportPath = args.report().toAbsolutePath();
    EvalReport report = Json.read(reportPath, EvalReport.class, "评测报告");
    GateTier tier =
        args.tier() != null && !args.tier().isBlank()
            ? GateTier.parse(args.tier(), report.k(), report.depth())
            : report.tier() != null
                ? GateTier.parse(report.tier(), report.k(), report.depth())
                : GateTier.resolve(report.k(), report.depth());
    EvalReport.Gate gate =
        GateEvaluator.evaluate(report, GatePolicy.of(tier), args.crosscheckerEnabled());
    EvalReport rejudged =
        new EvalReport(
            report.datasetPath(),
            report.cliJar(),
            report.depth(),
            report.k(),
            report.llmConfigured(),
            report.generatedAt(),
            report.blockedNote(),
            report.sets(),
            tier.id(),
            report.commit(),
            gate);
    ReportWriter.printLines(
        List.of(
            "== cra-eval gate（离线重判，不重跑 LLM） ==",
            "源报告: " + reportPath + "  sha256=" + ReportHasher.sha256(reportPath),
            "源报告档位: "
                + (report.tier() == null ? "(未标注)" : report.tier())
                + " → 重判档位: "
                + tier.id()));
    ReportWriter.renderGate(gate);
    if (args.jsonOut() != null) {
      Path out = args.jsonOut().toAbsolutePath();
      mkdirs(out.getParent());
      Json.write(out, rejudged);
      ReportWriter.printLines(List.of("重判报告: " + out));
      if (args.sha256Out() != null) {
        ReportWriter.printLines(
            List.of("重判报告哈希 (SHA-256): " + ReportHasher.writeSha256File(out, args.sha256Out())));
      }
    }
    ReportWriter.printLines(
        List.of("门禁结论: " + (gate.passed() ? "绿" : "红") + "（tier=" + tier.id() + "）"));
    return gate.passed() ? 0 : EvalCli.GATE_RED_EXIT;
  }

  private static void mkdirs(Path dir) {
    if (dir == null) {
      return;
    }
    try {
      java.nio.file.Files.createDirectories(dir);
    } catch (java.io.IOException e) {
      throw new CraConfigException("重判报告目录创建失败: " + dir + "（" + e.getMessage() + "）");
    }
  }
}
