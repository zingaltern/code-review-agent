package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cra.common.attest.ReportSignature;
import org.cra.eval.api.EvalReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** verify 子命令单测（T3.3：CI 校验本地 L2 报告——哈希 + 门禁绿 + commit 绑定）。 */
class VerifyCommandTest {

  @TempDir Path tmp;

  private static EvalReport.ProportionMetric metric(long numerator, long denominator) {
    double[] w = WilsonInterval.wilson95(numerator, denominator);
    return new EvalReport.ProportionMetric(
        numerator, denominator, (double) numerator / denominator, w[0], w[1]);
  }

  private static EvalReport report(long detected, boolean storedGatePassed) {
    EvalReport.SetStat bad =
        new EvalReport.SetStat(
            "bad",
            null,
            12,
            24,
            24,
            0,
            metric(detected, 24),
            null, // bug-only 观察指标
            null,
            null,
            null,
            new EvalReport.ZeroPublish(1, 24, 1.0 / 24, 0, 12, 0.0, 1.0 / 12, false),
            true,
            List.of(),
            new EvalReport.RunMetrics(0, 0, 0, 0, 0, 0, 0));
    EvalReport.GateCheck staleCheck =
        new EvalReport.GateCheck(
            "bad_detection", storedGatePassed ? "pass" : "red", 0.6, 0.55, "旧阈值判定");
    return new EvalReport(
        "ds",
        "cli.jar",
        "quick",
        2,
        true,
        "2026-10-25T00:00:00Z",
        null,
        List.of(bad),
        "l2",
        "abc1234",
        new EvalReport.Gate("l2", storedGatePassed, List.of(staleCheck)));
  }

  private Path writeReport(String name, EvalReport report) throws Exception {
    Path path = tmp.resolve(name);
    Json.MAPPER.writeValue(path.toFile(), report);
    return path;
  }

  private EvalCliArgs.VerifyArgs args(Path report, String sha, String tier, String commit) {
    return new EvalCliArgs.VerifyArgs(report, sha, null, tier, commit, null, null, null, null);
  }

  private EvalCliArgs.VerifyArgs signatureArgs(
      Path report, Path signature, Path keyFile, String tier, String commit) {
    return new EvalCliArgs.VerifyArgs(
        report, null, null, tier, commit, signature, null, keyFile, null);
  }

  private Path writeKey(String value) throws Exception {
    Path key = tmp.resolve("signing.key");
    Files.writeString(key, value);
    return key;
  }

  private Path writeSignature(Path report, Path keyFile, String commit, String tier)
      throws Exception {
    byte[] secret =
        Files.readString(keyFile).strip().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ReportSignature.Envelope envelope =
        ReportSignature.sign(
            secret,
            "test-key-v1",
            ReportSignature.sha256(Files.readAllBytes(report)),
            commit,
            tier,
            "2026-10-26T21:00:00Z",
            "att_test");
    Path signature = tmp.resolve("report.json.sig");
    Files.writeString(signature, envelope.text());
    return signature;
  }

  @Test
  void greenReportWithMatchingHashAndCommitPasses() throws Exception {
    Path path = writeReport("l2.json", report(19, true));
    String hash = ReportHasher.sha256(path);
    int code = VerifyCommand.execute(args(path, hash, "l2", "abc1234"));
    assertThat(code).isZero();
  }

  @Test
  void tamperedReportFailsHashCheck() throws Exception {
    Path path = writeReport("l2.json", report(19, true));
    String hash = ReportHasher.sha256(path);
    Files.writeString(path, Files.readString(path).replace("abc1234", "deadbeef"));
    int code = VerifyCommand.execute(args(path, hash, "l2", "abc1234"));
    assertThat(code).isEqualTo(5);
  }

  @Test
  void redGateFailsVerificationEvenWithValidHash() throws Exception {
    Path path = writeReport("l2-red.json", report(12, false)); // 12/24 → 下界 0.34 < 重冻结基线 0.35
    String hash = ReportHasher.sha256(path);
    assertThat(VerifyCommand.execute(args(path, hash, "l2", "abc1234"))).isEqualTo(5);
  }

  @Test
  void tierOrCommitMismatchFailsVerification() throws Exception {
    Path path = writeReport("l2.json", report(19, true));
    String hash = ReportHasher.sha256(path);
    assertThat(VerifyCommand.execute(args(path, hash, "l3", "abc1234"))).isEqualTo(5);
    assertThat(VerifyCommand.execute(args(path, hash, "l2", "deadbeef"))).isEqualTo(5);
  }

  @Test
  void hashFileModeIsSupported() throws Exception {
    Path path = writeReport("l2.json", report(19, true));
    Path hashFile = tmp.resolve("l2.json.sha256");
    ReportHasher.writeSha256File(path, hashFile);
    EvalCliArgs.VerifyArgs args =
        new EvalCliArgs.VerifyArgs(path, null, hashFile, "l2", "abc1234", null, null, null, null);
    assertThat(VerifyCommand.execute(args)).isZero();
  }

  @Test
  void serverSignatureVerifiesReport() throws Exception {
    Path path = writeReport("l2-signed.json", report(19, true));
    Path key = writeKey("integration-signing-key\n");
    Path signature = writeSignature(path, key, "abc1234", "l2");
    assertThat(VerifyCommand.execute(signatureArgs(path, signature, key, "l2", "abc1234")))
        .isZero();
  }

  @Test
  void tamperedReportFailsSignatureVerification() throws Exception {
    Path path = writeReport("l2-signed.json", report(19, true));
    Path key = writeKey("integration-signing-key");
    Path signature = writeSignature(path, key, "abc1234", "l2");
    Files.writeString(path, Files.readString(path).replace("abc1234", "deadbeef"));
    assertThat(VerifyCommand.execute(signatureArgs(path, signature, key, "l2", "deadbeef")))
        .isEqualTo(5);
  }

  @Test
  void tamperedEnvelopeFailsSignatureVerification() throws Exception {
    Path path = writeReport("l2-signed.json", report(19, true));
    Path key = writeKey("integration-signing-key");
    Path signature = writeSignature(path, key, "abc1234", "l2");
    Files.writeString(
        signature, Files.readString(signature).replace("commit:abc1234", "commit:deadbeef"));
    assertThat(VerifyCommand.execute(signatureArgs(path, signature, key, "l2", "abc1234")))
        .isEqualTo(5);
  }

  @Test
  void wrongSigningKeyFailsSignatureVerification() throws Exception {
    Path path = writeReport("l2-signed.json", report(19, true));
    Path key = writeKey("integration-signing-key");
    Path signature = writeSignature(path, key, "abc1234", "l2");
    Path otherKey = writeKey("another-signing-key");
    assertThat(VerifyCommand.execute(signatureArgs(path, signature, otherKey, "l2", "abc1234")))
        .isEqualTo(5);
  }

  @Test
  void signatureWithMismatchedTierFails() throws Exception {
    Path path = writeReport("l2-signed.json", report(19, true));
    Path key = writeKey("integration-signing-key");
    Path signature = writeSignature(path, key, "abc1234", "l3");
    assertThat(VerifyCommand.execute(signatureArgs(path, signature, key, "l2", "abc1234")))
        .isEqualTo(5);
  }
}
