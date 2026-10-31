package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.common.attest.ReportSignature;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.EvalCli;
import org.cra.eval.api.EvalReport;

/**
 * {@code cra-eval verify} 执行（T3.3 L2 门禁：CI 校验本地报告，04§2.4；M4b：服务端签名校验）。
 *
 * <p>校验项：① 报告文件 SHA-256 与作者提交的哈希一致（给了哈希时）；② 报告内门禁结论为绿；③ 可选字段绑定——{@code --tier} 要求档位一致、{@code
 * --expect-commit} 要求报告 commit 等于 PR head；④ {@code --signature} 要求服务端 HMAC 签名可验、摘要一致、
 * commit/tier/key_id 绑定一致。
 *
 * <p><strong>局限（如实声明）</strong>：无签名时仍是荣誉制校验，防传输/仓库内篡改，不防伪造（04§2.4）。有服务端签名时，作者无法在不知道 HMAC
 * 密钥的情况下伪造签名；签名仍只证明"持有服务端密钥者签发了该摘要"，不证明报告由服务端执行（ADR-0008）。
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
    EvalReport.Gate stored = report.gate();
    GateTier tier;
    if (args.tier() != null && !args.tier().isBlank()) {
      tier = GateTier.parse(args.tier(), report.k(), report.depth());
    } else if (stored != null && stored.tier() != null) {
      tier = GateTier.parse(stored.tier(), report.k(), report.depth());
    } else {
      tier = GateTier.resolve(report.k(), report.depth());
    }
    // 按**当前**阈值重算：报告是数据产物、门禁是策略——阈值变更后旧报告不得凭陈旧 gate 通过
    EvalReport.Gate gate = GateEvaluator.evaluate(report, GatePolicy.of(tier), false);
    lines.add(
        String.format(
            Locale.ROOT, "门禁: %s（tier=%s；按当前阈值重算）", gate.passed() ? "绿" : "红", gate.tier()));
    if (stored != null && stored.passed() != gate.passed()) {
      lines.add("（存量 gate=" + (stored.passed() ? "绿" : "红") + "，与当前阈值判定不一致——以重算结果为准，报告产出于旧阈值）");
    }
    if (!gate.passed()) {
      for (EvalReport.GateCheck c : gate.checks()) {
        if (GateEvaluator.RED.equals(c.status())) {
          lines.add("  [红] " + c.id() + " — " + c.detail());
        }
      }
      failures.add("报告门禁结论为红");
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
    if (args.signature() != null) {
      verifySignature(args, report, gate, actualHash, lines, failures);
    } else {
      lines.add("服务端签名: 未提供（荣誉制校验，04§2.4；服务化形态用 --signature）");
    }
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

  private static void verifySignature(
      EvalCliArgs.VerifyArgs args,
      EvalReport report,
      EvalReport.Gate gate,
      String actualHash,
      List<String> lines,
      List<String> failures) {
    byte[] secret = readSigningKey(args);
    ReportSignature.Envelope envelope;
    try {
      envelope = ReportSignature.parse(Files.readString(args.signature(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new CraConfigException("签名信封读取失败: " + args.signature() + "（" + e.getMessage() + "）");
    } catch (IllegalArgumentException e) {
      failures.add("签名信封格式非法: " + e.getMessage());
      return;
    }
    lines.add(
        "服务端签名: key_id="
            + envelope.keyId()
            + "，签发于 "
            + envelope.issuedAt()
            + "，run="
            + envelope.runId());
    if (!ReportSignature.verify(secret, envelope, actualHash)) {
      failures.add("签名校验失败（摘要不一致或 HMAC 不匹配——报告或信封被篡改）");
      return;
    }
    lines.add("签名校验: 一致（HMAC-SHA256）");
    if (args.signingKeyId() != null
        && !args.signingKeyId().isBlank()
        && !args.signingKeyId().equals(envelope.keyId())) {
      failures.add("签名 key_id 与 --signing-key-id 不一致（期望 " + args.signingKeyId() + "）");
    }
    String reportCommit = report.commit() == null ? "" : report.commit().strip();
    if (reportCommit.isEmpty()) {
      failures.add("报告缺 commit 绑定：服务端签名无法绑定代码提交（04§2.4）");
    } else if (!reportCommit.equalsIgnoreCase(envelope.commit())) {
      failures.add("签名信封 commit 与报告不一致（信封 " + envelope.commit() + "，报告 " + reportCommit + "）");
    } else {
      lines.add("签名 commit 绑定: 一致");
    }
    String expectedRepo = args.repo() == null ? "" : args.repo().strip();
    if (!expectedRepo.isEmpty()) {
      if (!expectedRepo.equalsIgnoreCase(envelope.repo())) {
        failures.add("签名信封 repo 与 --repo 不一致（信封 " + envelope.repo() + "，期望 " + expectedRepo + "）");
      } else {
        lines.add("签名 repo 绑定: 一致（" + envelope.repo() + "）");
      }
    } else {
      lines.add("签名 repo 绑定: 信封 " + envelope.repo() + "（未提供 --repo，不做期望值比对）");
    }
    String effectiveTier = gate.tier() == null ? "" : gate.tier();
    if (envelope.tier().isEmpty()) {
      failures.add("签名信封缺 tier 绑定（04§2.4）");
    } else if (!envelope.tier().equalsIgnoreCase(effectiveTier)) {
      failures.add("签名信封 tier 与门禁档位不一致（信封 " + envelope.tier() + "，判定 " + effectiveTier + "）");
    } else {
      lines.add("签名 tier 绑定: 一致（" + envelope.tier() + "）");
    }
    lines.add("签名语义: 防伪（密钥仅服务端与 CI 持有）；托管执行能力见 ADR-0008");
  }

  private static byte[] readSigningKey(EvalCliArgs.VerifyArgs args) {
    if (args.signingKeyFile() != null) {
      try {
        String key = Files.readString(args.signingKeyFile(), StandardCharsets.UTF_8).strip();
        if (key.isEmpty()) {
          throw new CraConfigException("签名密钥文件为空: " + args.signingKeyFile());
        }
        return key.getBytes(StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new CraConfigException(
            "签名密钥文件读取失败: " + args.signingKeyFile() + "（" + e.getMessage() + "）");
      }
    }
    String envName = args.signingKeyEnv();
    String key = envName == null ? null : System.getenv(envName);
    if (key == null || key.isBlank()) {
      throw new CraConfigException(
          "签名密钥不可得: 环境变量 "
              + (envName == null ? VerifyArgsParser.DEFAULT_SIGNING_KEY_ENV : envName)
              + " 为空（或用 --signing-key-file；05§6 密钥只从环境变量/文件读）");
    }
    return key.strip().getBytes(StandardCharsets.UTF_8);
  }

  private static String normalize(String hex) {
    return hex == null ? "" : hex.strip().toLowerCase(Locale.ROOT);
  }
}
