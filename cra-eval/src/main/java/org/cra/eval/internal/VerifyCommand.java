package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.EvalCli;
import org.cra.eval.api.EvalReport;

/**
 * {@code cra-eval verify} 执行（T3.3 L2 门禁：CI 校验本地报告，04§2.4）。
 *
 * <p>校验三件事：① 报告文件 SHA-256 与作者提交的哈希一致；② 报告内门禁结论为绿；③ 可选字段绑定—— {@code --tier} 要求档位一致、{@code
 * --expect-commit} 要求报告 commit 等于 PR head。任一不满足 → 退出码 5。
 *
 * <p><strong>局限（如实声明）</strong>：这是荣誉制校验，防传输/仓库内篡改，不防伪造——作者可以伪造报告与哈希 （04§2.4、03§5）。彻底消除需 M4b
 * 服务端托管执行与签名。
 */
public final class VerifyCommand {

  private VerifyCommand() {}

  /**
   * 执行校验。
   *
   * @param args 已解析参数
   * @return 退出码（0 = 通过；5 = 校验红）
   */
  public static int execute(EvalCliArgs.VerifyArgs args) {
    Path reportPath = args.report().toAbsolutePath();
    if (!Files.isRegularFile(reportPath)) {
      throw new CraConfigException("报告不存在: " + reportPath);
    }
    String actualHash = ReportHasher.sha256(reportPath);
    List<String> lines = new ArrayList<>();
    lines.add("== cra-eval verify ==");
    lines.add("报告: " + reportPath);
    lines.add("sha256: " + actualHash);

    List<String> failures = new ArrayList<>();
    if (args.sha256() != null && !args.sha256().isBlank()) {
      if (!ReportHasher.matches(reportPath, args.sha256())) {
        failures.add("报告哈希与 --sha256 不一致（期望 " + normalize(args.sha256()) + "）");
      } else {
        lines.add("哈希校验: 一致（--sha256）");
      }
    }
    if (args.sha256File() != null) {
      String expected = ReportHasher.readSha256File(args.sha256File());
      if (!actualHash.equals(expected)) {
        failures.add("报告哈希与哈希文件不一致（期望 " + expected + "）");
      } else {
        lines.add("哈希校验: 一致（" + args.sha256File() + "）");
      }
    }

    EvalReport report = Json.read(reportPath, EvalReport.class, "评测报告");
    EvalReport.Gate gate = report.gate();
    if (gate == null) {
      failures.add("报告缺少 gate 结论（旧版报告不可用于门禁）");
    } else {
      lines.add(
          String.format(Locale.ROOT, "门禁: %s（tier=%s）", gate.passed() ? "绿" : "红", gate.tier()));
      if (!gate.passed()) {
        for (EvalReport.GateCheck c : gate.checks()) {
          if (GateEvaluator.RED.equals(c.status())) {
            lines.add("  [红] " + c.id() + " — " + c.detail());
          }
        }
        failures.add("报告门禁结论为红");
      }
      if (args.tier() != null && !args.tier().isBlank() && !args.tier().equals(gate.tier())) {
        failures.add("报告档位 " + gate.tier() + " ≠ 要求档位 " + args.tier());
      }
    }
    if (args.expectCommit() != null && !args.expectCommit().isBlank()) {
      String commit = report.commit();
      lines.add("报告 commit: " + (commit == null ? "(缺失)" : commit));
      if (commit == null || !commit.equalsIgnoreCase(args.expectCommit())) {
        failures.add("报告 commit 与 --expect-commit 不一致（期望 " + args.expectCommit() + "）");
      } else {
        lines.add("commit 绑定: 一致");
      }
    }
    lines.add("荣誉制声明: 哈希校验防篡改不防伪造——不能证明报告由当前 HEAD 真实生成（04§2.4；彻底消除待 M4b 服务端签名）");
    if (failures.isEmpty()) {
      lines.add("结论: 通过");
      ReportWriter.printLines(lines);
      return 0;
    }
    for (String failure : failures) {
      lines.add("  × " + failure);
    }
    lines.add("结论: 红");
    ReportWriter.printLines(lines);
    return EvalCli.GATE_RED_EXIT;
  }

  private static String normalize(String hex) {
    return hex == null ? "" : hex.strip().toLowerCase(Locale.ROOT);
  }
}
