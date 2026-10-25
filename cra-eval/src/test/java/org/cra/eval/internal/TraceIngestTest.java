package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.cra.common.git.RepoRef;
import org.junit.jupiter.api.Test;

/** trace 读取（05§1.4）：run_start/promotion/judge/run_end 事件 → 周报漏斗与拒真池事实。 */
class TraceIngestTest {

  private static final Path TRACES = Path.of("src/test/resources/report-fixture/traces");

  @Test
  void parsesPromotionsJudgesAndRunEnd() {
    List<TraceIngest.RunTrace> traces =
        TraceIngest.scan(
            List.of(TRACES), new RepoRef("acme", "demo"), Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(traces).hasSize(2);
    TraceIngest.RunTrace runA =
        traces.stream().filter(t -> t.runId().equals("run_101")).findFirst().orElseThrow();
    WindowData.RunFacts factsA = TraceIngest.toFacts(runA);
    assertThat(factsA.candidates()).isEqualTo(30);
    assertThat(factsA.confirmed()).isEqualTo(20);
    assertThat(factsA.published()).isEqualTo(16);
    assertThat(factsA.suppressedByTruncation()).isEqualTo(2);
    assertThat(factsA.refutedTotal()).isEqualTo(7);
    assertThat(factsA.judgeUnavailable()).isEqualTo(1);
    assertThat(factsA.judgedRefusals()).hasSize(5);
    assertThat(
            factsA.judgedRefusals().stream().filter(WindowData.RefusalSample::overturned).count())
        .isEqualTo(2);
    assertThat(factsA.promotionData()).isTrue();
    assertThat(factsA.dryRun()).isFalse();

    TraceIngest.RunTrace runB =
        traces.stream().filter(t -> t.runId().equals("run_102")).findFirst().orElseThrow();
    WindowData.RunFacts factsB = TraceIngest.toFacts(runB);
    assertThat(factsB.candidates()).isEqualTo(15);
    assertThat(factsB.confirmed()).isEqualTo(11);
    assertThat(factsB.refutedTotal()).isEqualTo(5);
    assertThat(factsB.judgedRefusals()).hasSize(3);
  }

  @Test
  void filtersByRepoAndWindow() {
    assertThat(
            TraceIngest.scan(
                List.of(TRACES),
                new RepoRef("other", "repo"),
                Instant.parse("2026-10-01T00:00:00Z")))
        .isEmpty();
    assertThat(
            TraceIngest.scan(
                List.of(TRACES),
                new RepoRef("acme", "demo"),
                Instant.parse("2026-10-24T00:00:00Z")))
        .isEmpty();
  }
}
