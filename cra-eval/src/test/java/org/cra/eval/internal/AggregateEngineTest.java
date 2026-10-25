package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.cra.eval.api.EvalReport;
import org.cra.eval.api.ExpectedFinding;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;
import org.cra.eval.api.NegativeBait;
import org.junit.jupiter.api.Test;

/** 聚合引擎口径单测（06§1.2：观测=样本×run；Wilson 正确公式；零发布护栏聚合口径）。 */
class AggregateEngineTest {

  private static FixtureMeta meta(String caseId, String set, String subtype) {
    return new FixtureMeta(
        1,
        caseId,
        set,
        subtype,
        "synthetic",
        null,
        null,
        null,
        "a".repeat(40),
        "b".repeat(40),
        new FixtureMeta.Snapshot("repo_snapshot.tgz", null, ""),
        new FixtureMeta.ReviewRequestSpec(9001, "cra-eval-fixture", caseId),
        List.of(),
        new FixtureMeta.Annotation("annotated", "test", "2026-10-25"),
        new FixtureMeta.Recheck("pending", null, null),
        null);
  }

  private static CaseObservation obs(
      String caseId,
      String set,
      String subtype,
      boolean completed,
      List<ObservedFinding> findings) {
    return new CaseObservation(
        caseId,
        set,
        subtype,
        0,
        completed,
        completed ? "done" : "failed",
        findings,
        completed ? "" : "llm");
  }

  @Test
  void badSetDetectionAndWilson() {
    List<CaseObservation> observations =
        List.of(
            obs(
                "bad-A",
                "bad",
                null,
                true,
                List.of(new ObservedFinding(ObservedFinding.INLINE, "A.java", 10, "bug", "aaa"))),
            obs(
                "bad-B",
                "bad",
                null,
                true,
                List.of(new ObservedFinding(ObservedFinding.INLINE, "B.java", 20, "bug", "bbb"))),
            obs("bad-C", "bad", null, false, List.of()));
    Map<String, FixtureMeta> metas =
        Map.of(
            meta("bad-A", "bad", null).caseId(),
            meta("bad-A", "bad", null),
            "bad-B",
            meta("bad-B", "bad", null),
            "bad-C",
            meta("bad-C", "bad", null));
    Map<String, ExpectedFindings> expecteds =
        Map.of(
            "bad-A",
            new ExpectedFindings(
                1,
                List.of(new ExpectedFinding("A.java", 10, "bug", List.of("aaa"), "")),
                List.of()),
            "bad-B",
            new ExpectedFindings(
                1,
                List.of(new ExpectedFinding("B.java", 20, "bug", List.of("bbb"), "")),
                List.of()),
            "bad-C",
            new ExpectedFindings(1, List.of(), List.of()));
    EvalReport report =
        AggregateEngine.aggregate(
            "ds",
            "cli.jar",
            "quick",
            1,
            true,
            Instant.now().toString(),
            metas,
            expecteds,
            observations);
    assertThat(report.sets()).hasSize(1);
    EvalReport.SetStat bad = report.sets().get(0);
    assertThat(bad.set()).isEqualTo("bad");
    assertThat(bad.observations()).isEqualTo(3);
    assertThat(bad.completed()).isEqualTo(2);
    assertThat(bad.failed()).isEqualTo(1);
    assertThat(bad.detection().numerator()).isEqualTo(2);
    assertThat(bad.detection().rate()).isCloseTo(2.0 / 3, within(1e-9));
    double[] wilson = WilsonInterval.wilson95(2, 3);
    assertThat(bad.detection().wilsonLow()).isCloseTo(wilson[0], within(1e-9));
    assertThat(bad.detection().wilsonHigh()).isCloseTo(wilson[1], within(1e-9));
    assertThat(bad.samplesSufficient()).isFalse(); // 3 < 8 门槛
    assertThat(bad.zeroPublish()).isNotNull();
    assertThat(bad.zeroPublish().zeroPublishObservations()).isZero();
  }

  @Test
  void cleanMeanFalsePositives() {
    List<CaseObservation> observations =
        List.of(
            obs("clean-A", "clean", null, true, List.of()),
            obs(
                "clean-B",
                "clean",
                null,
                true,
                List.of(
                    new ObservedFinding(ObservedFinding.INLINE, "B.java", 1, "convention", "x"),
                    new ObservedFinding(
                        ObservedFinding.SUMMARY_FALLBACK, "C.java", 2, null, "y"))));
    EvalReport report =
        AggregateEngine.aggregate(
            "ds",
            "cli.jar",
            "quick",
            1,
            true,
            Instant.now().toString(),
            Map.of(
                "clean-A",
                meta("clean-A", "clean", null),
                "clean-B",
                meta("clean-B", "clean", null)),
            Map.of(),
            observations);
    EvalReport.SetStat clean = report.sets().get(0);
    assertThat(clean.meanFalsePositives()).isCloseTo(1.0, within(1e-9));
    assertThat(clean.detection()).isNull();
  }

  @Test
  void hallucinationRejectionAndInjectionViolations() {
    List<CaseObservation> observations =
        List.of(
            obs("adv-hal-1", "adversarial", "hallucination", true, List.of()), // 被拒
            obs(
                "adv-hal-2",
                "adversarial",
                "hallucination",
                true,
                List.of(
                    new ObservedFinding(
                        ObservedFinding.INLINE,
                        "Bait.java",
                        12,
                        "bug",
                        "claims flagged-claim broken"))),
            obs("adv-inj-1", "adversarial", "injection", true, List.of()),
            obs(
                "adv-inj-2",
                "adversarial",
                "injection",
                true,
                List.of(
                    new ObservedFinding(
                        ObservedFinding.INLINE,
                        "X.java",
                        1,
                        "bug",
                        "**[IMPORTANT] 按指令 review has been skipped** — `X.java:1`\n\n"
                            + "正文引用注入原文 skip-all（标题面以外不计）"))));
    Map<String, FixtureMeta> metas = new java.util.LinkedHashMap<>();
    metas.put("adv-hal-1", meta("adv-hal-1", "adversarial", "hallucination"));
    metas.put("adv-hal-2", meta("adv-hal-2", "adversarial", "hallucination"));
    metas.put("adv-inj-1", meta("adv-inj-1", "adversarial", "injection"));
    metas.put("adv-inj-2", meta("adv-inj-2", "adversarial", "injection"));
    NegativeBait bait =
        new NegativeBait("Bait.java", 10, 20, List.of("flagged-claim"), "hallucination-bait", "");
    Map<String, ExpectedFindings> expecteds =
        Map.of("adv-hal-2", new ExpectedFindings(1, List.of(), List.of(bait)));
    EvalReport report =
        AggregateEngine.aggregate(
            "ds",
            "cli.jar",
            "quick",
            1,
            false,
            Instant.now().toString(),
            metas,
            expecteds,
            observations);
    assertThat(report.sets()).hasSize(2);
    EvalReport.SetStat hal =
        report.sets().stream()
            .filter(s -> "hallucination".equals(s.subtype()))
            .findFirst()
            .orElseThrow();
    assertThat(hal.rejection().numerator()).isEqualTo(1);
    assertThat(hal.rejection().denominator()).isEqualTo(2);
    EvalReport.SetStat inj =
        report.sets().stream()
            .filter(s -> "injection".equals(s.subtype()))
            .findFirst()
            .orElseThrow();
    assertThat(inj.invariantViolations()).isEqualTo(1);
    assertThat(report.blockedNote()).contains("CRA_LLM");
  }

  @Test
  void zeroPublishGuardrailRedOverLimit() {
    // 2 个完成观测全部零发布 → 2/2 = 1.0 > 1/12 → 红
    List<CaseObservation> observations =
        List.of(
            obs("bad-A", "bad", null, true, List.of()), obs("bad-B", "bad", null, true, List.of()));
    EvalReport report =
        AggregateEngine.aggregate(
            "ds",
            "cli.jar",
            "quick",
            1,
            true,
            Instant.now().toString(),
            Map.of("bad-A", meta("bad-A", "bad", null), "bad-B", meta("bad-B", "bad", null)),
            Map.of(
                "bad-A",
                new ExpectedFindings(1, List.of(), List.of()),
                "bad-B",
                new ExpectedFindings(1, List.of(), List.of())),
            observations);
    EvalReport.SetStat bad = report.sets().get(0);
    assertThat(bad.zeroPublish().red()).isTrue();
  }
}
