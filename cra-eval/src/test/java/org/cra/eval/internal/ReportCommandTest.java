package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.cra.eval.api.EvalCli;
import org.cra.eval.api.WeeklyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code cra-eval report} 端到端（离线回放）：参数 → 取数 → 聚合 → 文本/JSON（T3.4 验收 2）。 */
class ReportCommandTest {

  @org.junit.jupiter.api.BeforeAll
  static void pinReplayTime() {
    // fixture 的合并日期是固定的；不钉住"现在"，2026-10-30 起 PR-104 越过 7 天 ignored 窗口，分母会漂移
    System.setProperty("cra.eval.replayNow", "2026-10-24T00:00:00Z");
  }

  @org.junit.jupiter.api.AfterAll
  static void clearReplayTime() {
    System.clearProperty("cra.eval.replayNow");
  }

  @TempDir Path tmp;

  @Test
  void replayReportProducesJsonAndHonestSampleNotes() {
    Path fixture = Path.of("src/test/resources/report-fixture");
    Path out = tmp.resolve("weekly.json");
    int code =
        EvalCli.execute(
            new String[] {
              "report",
              "--repo",
              "acme/demo",
              "--since",
              "52w",
              "--ledger-fixture",
              fixture.toString(),
              "--trace-dir",
              fixture.resolve("traces").toString(),
              "--dataset",
              fixture.resolve("dataset").toString(),
              "--previous",
              fixture.resolve("previous/2026-10-11.json")
                  + ","
                  + fixture.resolve("previous/2026-10-18.json"),
              "--json",
              out.toString()
            },
            Map.of());
    assertThat(code).isZero();
    assertThat(out).exists();
    WeeklyReport report = Json.read(out, WeeklyReport.class, "weekly");
    assertThat(report.dataSource()).isEqualTo("ledger-fixture");
    assertThat(report.prs()).isEqualTo(4);
    assertThat(report.disposition().samplesIncludingIgnored()).isEqualTo(38);
    assertThat(report.funnel().candidateDataAvailable()).isTrue();
    assertThat(report.refusal().pool()).isEqualTo(8);
    assertThat(report.dualVerifierGate().triggered()).isFalse();
    assertThat(report.notes()).anyMatch(n -> n.contains("resolve"));
    assertThat(report.notes()).anyMatch(n -> n.contains("Summary"));
  }

  @Test
  void reportWithoutTokenOrFixtureFailsLoudly() {
    int code = EvalCli.execute(new String[] {"report", "--repo", "acme/demo"}, Map.of());
    assertThat(code).isEqualTo(2);
  }
}
