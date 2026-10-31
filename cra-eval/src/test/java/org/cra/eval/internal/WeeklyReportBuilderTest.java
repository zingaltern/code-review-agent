package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.eval.api.WeeklyReport;
import org.junit.jupiter.api.Test;

/** 周报聚合口径（06§1.1）：四类处置、两套分母、样本门槛、拒真池、漏斗、零发布趋势与 T3.6 门槛。 */
class WeeklyReportBuilderTest {

  private static final Path FIXTURE = Path.of("src/test/resources/report-fixture");
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

  private static WeeklyReport fixtureReport() {
    WindowData.Window base =
        new ReplayWindowSource(FIXTURE)
            .load(new RepoRef("acme", "demo"), NOW.minusSeconds(30L * 86400));
    List<WindowData.RunFacts> runs = new ArrayList<>();
    for (TraceIngest.RunTrace trace :
        TraceIngest.scan(
            List.of(FIXTURE.resolve("traces")),
            new RepoRef("acme", "demo"),
            NOW.minusSeconds(30L * 86400))) {
      runs.add(TraceIngest.toFacts(trace));
    }
    WindowData.Window window =
        new WindowData.Window(
            base.repo(),
            base.source(),
            base.prs(),
            runs,
            BadcaseIndex.scan(FIXTURE.resolve("dataset")),
            base.notes());
    List<WeeklyReport> previous =
        List.of(
            Json.read(FIXTURE.resolve("previous/2026-10-11.json"), WeeklyReport.class, "previous"),
            Json.read(FIXTURE.resolve("previous/2026-10-18.json"), WeeklyReport.class, "previous"));
    return WeeklyReportBuilder.build(window, NOW, "30d", NOW.minusSeconds(30L * 86400), previous);
  }

  /** S5961 拆分：原 43 断言按报告段落拆为 5 个用例，断言总量不变。 */
  @Test
  void dispositionCountsAndRatesMatchFixture() {
    WeeklyReport.Disposition d = fixtureReport().disposition();
    assertThat(d.fixed()).isEqualTo(31);
    assertThat(d.wrong()).isEqualTo(2);
    assertThat(d.intentional()).isEqualTo(2);
    assertThat(d.ignored()).isEqualTo(3);
    assertThat(d.pending()).isEqualTo(6);
    assertThat(d.explicitSamples()).isEqualTo(35);
    assertThat(d.samplesIncludingIgnored()).isEqualTo(38);
    assertThat(d.precision().numerator()).isEqualTo(33);
    assertThat(d.precision().denominator()).isEqualTo(38);
    assertThat(d.precision().rate())
        .isCloseTo(33.0 / 38.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(d.falsePositive().rate())
        .isCloseTo(2.0 / 38.0, org.assertj.core.data.Offset.offset(1e-9));
    // 附口径：只含有向处置
    assertThat(d.precisionExplicitOnly().denominator()).isEqualTo(35);
    assertThat(d.samplesSufficient()).isTrue();
    assertThat(d.meetsPrecisionTarget()).isTrue();
    assertThat(d.meetsFalsePositiveLimit()).isTrue();
  }

  @Test
  void refusalPoolAndSampleGateMatchFixture() {
    WeeklyReport.Refusal r = fixtureReport().refusal();
    assertThat(r.refutedTotal()).isEqualTo(12);
    assertThat(r.judgeEvaluated()).isEqualTo(8);
    assertThat(r.judgeUnavailable()).isEqualTo(1);
    assertThat(r.pool()).isEqualTo(8);
    assertThat(r.overturnRate()).isCloseTo(0.375, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(r.samplesSufficient()).isFalse(); // <20 → 不下结论
  }

  @Test
  void funnelCountsMatchFixture() {
    WeeklyReport.Funnel f = fixtureReport().funnel();
    assertThat(f.candidateDataAvailable()).isTrue();
    assertThat(f.candidates()).isEqualTo(45);
    assertThat(f.confirmed()).isEqualTo(31);
    assertThat(f.published()).isEqualTo(26);
    assertThat(f.suppressedByTruncation()).isEqualTo(3);
    assertThat(f.truncatedRuns()).isEqualTo(2);
  }

  @Test
  void zeroPublishSeriesMatchesFixture() {
    WeeklyReport.ZeroPublish z = fixtureReport().zeroPublish();
    assertThat(z.runs()).isEqualTo(5);
    assertThat(z.zeroRuns()).isEqualTo(1);
    assertThat(z.ratio()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(z.series()).containsExactly(0.10, 0.15, 0.20);
    assertThat(z.consecutiveRise()).isTrue();
    assertThat(z.trend()).isEqualTo("rising");
  }

  @Test
  void satisfactionAndDualVerifierGateMatchFixture() {
    WeeklyReport report = fixtureReport();
    assertThat(report.satisfaction().up()).isEqualTo(1);
    assertThat(report.satisfaction().down()).isEqualTo(1);
    assertThat(report.satisfaction().runsWithReaction()).isEqualTo(2);

    WeeklyReport.DualVerifierGate gate = report.dualVerifierGate();
    assertThat(gate.triggered()).isFalse();
    assertThat(gate.decision()).contains("不开启");
    assertThat(gate.refusalCondition().met()).isFalse();
    assertThat(gate.refusalCondition().verdict()).contains("样本不足");
    assertThat(gate.dispositionCondition().met()).isFalse();
    assertThat(report.notes()).anyMatch(n -> n.contains("ignored"));
    assertThat(report.dataSource()).isEqualTo("ledger-fixture");
  }

  @Test
  void refusesToConcludeWhenDispositionSamplesBelowGate() {
    WindowData.Window window =
        new WindowData.Window(
            new RepoRef("o", "r"),
            "test",
            List.of(onePr(29, 0, 0)),
            List.of(),
            List.of(),
            List.of());
    WeeklyReport report =
        WeeklyReportBuilder.build(window, NOW, "7d", NOW.minusSeconds(604800), List.of());
    assertThat(report.disposition().samplesIncludingIgnored()).isEqualTo(29);
    assertThat(report.disposition().samplesSufficient()).isFalse();
    assertThat(report.disposition().meetsPrecisionTarget()).isNull();
    assertThat(report.dualVerifierGate().dispositionCondition().verdict()).contains("样本不足");
    assertThat(report.dualVerifierGate().triggered()).isFalse();
  }

  @Test
  void gateTriggersWhenBothThresholdsMet() {
    // 30 条全部 :wrong:（精确率 0%），拒真池 20 条中 2 条被 Judge 翻案（10%）→ 两个条件同时满足
    WindowData.Window window =
        new WindowData.Window(
            new RepoRef("o", "r"),
            "test",
            List.of(onePr(30, 0, 30)),
            List.of(runWithJudgedRefusals(20, 2)),
            List.of(),
            List.of());
    WeeklyReport report =
        WeeklyReportBuilder.build(window, NOW, "7d", NOW.minusSeconds(604800), List.of());
    WeeklyReport.DualVerifierGate gate = report.dualVerifierGate();
    assertThat(report.refusal().pool()).isEqualTo(20);
    assertThat(report.refusal().overturnRate())
        .isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(report.disposition().precision().rate()).isZero();
    assertThat(gate.refusalCondition().met()).isTrue();
    assertThat(gate.dispositionCondition().met()).isTrue();
    assertThat(gate.triggered()).isTrue();
    assertThat(gate.decision()).contains("满足开启条件");
  }

  // ---------- 构造最小窗口 ----------

  private static WindowData.PrWindow onePr(int findings, int fixed, int wrong) {
    List<LedgerFindingMarker> markers = new ArrayList<>();
    List<LedgerComment> comments = new ArrayList<>();
    List<DispositionSignal> signals = new ArrayList<>();
    for (int i = 1; i <= findings; i++) {
      long commentId = 1000 + i;
      markers.add(
          new LedgerFindingMarker(
              String.format("%04x", i),
              "src/F.java",
              i,
              "bug",
              "f_" + i,
              "run_1",
              "headsha",
              commentId));
      comments.add(
          new LedgerComment(
              commentId,
              LedgerComment.KIND_INLINE,
              "bot",
              "mock",
              NOW.minusSeconds(20L * 86400),
              "headsha"));
      if (i <= wrong) {
        signals.add(
            new DispositionSignal(
                DispositionSignal.KIND_REPLY_TAG,
                commentId,
                NOW.minusSeconds(10L * 86400),
                DispositionSignal.TAG_WRONG,
                "alice",
                ":wrong:"));
      }
    }
    PullRequestRef pull =
        new PullRequestRef(
            1,
            "headsha",
            "mergesha",
            PullRequestRef.STATE_CLOSED,
            NOW.minusSeconds(15L * 86400),
            NOW.minusSeconds(15L * 86400));
    CommentLedger ledger = new CommentLedger(comments, markers, List.of(), 0);
    Map<String, WindowData.TouchFacts> touches =
        Map.of("headsha", new WindowData.TouchFacts(java.util.Set.of(), java.util.Set.of()));
    return new WindowData.PrWindow(pull, ledger, signals, touches);
  }

  private static WindowData.RunFacts runWithJudgedRefusals(int pool, int overturned) {
    List<WindowData.RefusalSample> samples = new ArrayList<>();
    for (int i = 0; i < pool; i++) {
      samples.add(new WindowData.RefusalSample("f_r" + i, i < overturned));
    }
    return new WindowData.RunFacts(
        "run_1",
        "o/r",
        1,
        NOW.minusSeconds(86400),
        "success",
        false,
        pool,
        pool - overturned,
        0,
        0,
        pool,
        0,
        samples,
        true);
  }
}
