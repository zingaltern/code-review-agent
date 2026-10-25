package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** per-run 统计读取单测（T3.1：trace run_end → tokens/latency）。 */
class RunStatsReaderTest {

  @TempDir Path tmp;

  private static final String RUN_END =
      "{\"ts\":\"2026-10-25T00:00:00Z\",\"run_id\":\"r1\",\"event\":\"run_end\",\"status\":\"done\","
          + "\"findings_published\":2,\"tokens_in\":123456,\"tokens_out\":7890,\"latency_ms\":95000}";

  @Test
  void readsRunEndUnderRunRoot() throws Exception {
    Path runRoot = tmp.resolve("run-0");
    Path runDir = runRoot.resolve("run_20261025-abc");
    Files.createDirectories(runDir);
    Files.writeString(
        runDir.resolve("trace.ndjson"),
        "{\"event\":\"run_start\"}\n" + "{\"event\":\"agent_start\"}\n" + RUN_END + "\n");
    CaseObservation.RunStats stats = RunStatsReader.read(runRoot);
    assertThat(stats.available()).isTrue();
    assertThat(stats.tokensIn()).isEqualTo(123456);
    assertThat(stats.tokensOut()).isEqualTo(7890);
    assertThat(stats.latencyMs()).isEqualTo(95000);
  }

  @Test
  void missingOrMalformedTraceYieldsEmptyStats() throws Exception {
    assertThat(RunStatsReader.read(tmp.resolve("nope")).available()).isFalse();

    Path runRoot = tmp.resolve("run-1");
    Path runDir = runRoot.resolve("r");
    Files.createDirectories(runDir);
    Files.writeString(runDir.resolve("trace.ndjson"), "not-json\n{\"event\":\"run_start\"}\n");
    assertThat(RunStatsReader.read(runRoot).available()).isFalse();
  }

  @Test
  void lastRunEndWins() throws Exception {
    Path runRoot = tmp.resolve("run-2");
    Path runDir = runRoot.resolve("r");
    Files.createDirectories(runDir);
    String first = "{\"event\":\"run_end\",\"tokens_in\":1,\"tokens_out\":2,\"latency_ms\":3}";
    String second = "{\"event\":\"run_end\",\"tokens_in\":10,\"tokens_out\":20,\"latency_ms\":30}";
    Files.writeString(runDir.resolve("trace.ndjson"), first + "\n" + second + "\n");
    CaseObservation.RunStats stats = RunStatsReader.read(runRoot);
    assertThat(stats.tokensIn()).isEqualTo(10);
    assertThat(stats.tokensOut()).isEqualTo(20);
    assertThat(stats.latencyMs()).isEqualTo(30);
  }
}
