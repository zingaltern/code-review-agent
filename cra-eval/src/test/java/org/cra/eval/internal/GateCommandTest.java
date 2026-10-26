package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.cra.eval.api.EvalReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** gate 子命令单测（T3.1：已有报告离线重判；阈值变更不重跑 LLM）。 */
class GateCommandTest {

  @TempDir Path tmp;

  private static EvalReport.ProportionMetric metric(long numerator, long denominator) {
    double[] w = WilsonInterval.wilson95(numerator, denominator);
    return new EvalReport.ProportionMetric(
        numerator, denominator, (double) numerator / denominator, w[0], w[1]);
  }

  private static EvalReport report(long detected, long observations, boolean storedPassed)
      throws Exception {
    EvalReport.SetStat bad =
        new EvalReport.SetStat(
            "bad",
            null,
            1,
            observations,
            observations,
            0,
            metric(detected, observations),
            null,
            null,
            null,
            null,
            null,
            false,
            List.of(),
            new EvalReport.RunMetrics(0, 0, 0, 0, 0, 0, 0));
    EvalReport.Gate staleGate =
        new EvalReport.Gate(
            "l2",
            storedPassed,
            List.of(new EvalReport.GateCheck("bad_detection", "pass", 0.6, 0.55, "旧阈值")));
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
        staleGate);
  }

  private Path write(String name, EvalReport report) throws Exception {
    Path path = tmp.resolve(name);
    Json.MAPPER.writeValue(path.toFile(), report);
    return path;
  }

  @Test
  void rejudgesWithCurrentPolicyInsteadOfStoredGate() throws Exception {
    // 17/24 = 0.708，Wilson 下界 0.508：旧阈值 0.55 下为红，当前 0.40 基线应判绿
    Path path = write("restored.json", report(17, 24, false));
    Path out = tmp.resolve("out/rejudged.json");
    Path hash = tmp.resolve("out/rejudged.json.sha256");
    int code = GateCommand.execute(new EvalCliArgs.GateArgs(path, "l2", out, hash, false));
    assertThat(code).isZero();
    EvalReport rejudged = Json.MAPPER.readValue(out.toFile(), EvalReport.class);
    assertThat(rejudged.gate()).isNotNull();
    assertThat(rejudged.gate().passed()).isTrue();
    assertThat(rejudged.gate().checks()).extracting(EvalReport.GateCheck::threshold).contains(0.35);
    assertThat(rejudged.tier()).isEqualTo("l2");
    assertThat(rejudged.commit()).isEqualTo("abc1234");
    assertThat(ReportHasher.readSha256File(hash)).isEqualTo(ReportHasher.sha256(out));
  }

  @Test
  void redReportReturnsGateRedExit() throws Exception {
    Path path = write("broken.json", report(12, 24, true)); // 12/24 → 下界 0.34 < 0.35
    assertThat(GateCommand.execute(new EvalCliArgs.GateArgs(path, "l2", null, null, false)))
        .isEqualTo(5);
  }

  @Test
  void tierFallsBackToReportTier() throws Exception {
    Path path = write("tiered.json", report(17, 24, false));
    Path out = tmp.resolve("out2.json");
    assertThat(GateCommand.execute(new EvalCliArgs.GateArgs(path, null, out, null, false)))
        .isZero();
    EvalReport rejudged = Json.MAPPER.readValue(out.toFile(), EvalReport.class);
    assertThat(rejudged.tier()).isEqualTo("l2");
  }
}
