package org.cra.eval.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * per-run 统计读取器（T3.1 指标报告）：从被测 run 的 trace NDJSON（05§1.4）取 {@code run_end} 事件的 {@code
 * tokens_in}/{@code tokens_out}/{@code latency_ms}。
 *
 * <p>位置约定：eval 给 CLI 的 {@code --run-root} 是 {@code <workDir>/run-<k>}，CLI 在其下写 {@code
 * <run_id>/trace.ndjson}。缺失/不可读时返回 {@link org.cra.eval.internal.CaseObservation.RunStats#EMPTY}，
 * 观测进报告但不进 token/耗时均值分母（报告如实标注分母）。
 */
public final class RunStatsReader {

  private RunStatsReader() {}

  /**
   * 读取一次 run 的统计。
   *
   * @param runRoot 该次 run 的 trace 根目录（{@code <workDir>/run-<k>}）
   * @return 统计（取最后一条 run_end；缺失为 EMPTY）
   */
  public static CaseObservation.RunStats read(Path runRoot) {
    if (runRoot == null || !Files.isDirectory(runRoot)) {
      return CaseObservation.RunStats.EMPTY;
    }
    List<Path> traces;
    try (Stream<Path> walk = Files.walk(runRoot, 2)) {
      traces =
          walk.filter(Files::isRegularFile)
              .filter(p -> "trace.ndjson".equals(p.getFileName().toString()))
              .sorted(Comparator.comparing(Path::toString))
              .toList();
    } catch (IOException e) {
      return CaseObservation.RunStats.EMPTY;
    }
    CaseObservation.RunStats found = CaseObservation.RunStats.EMPTY;
    for (Path trace : traces) {
      CaseObservation.RunStats stats = lastRunEnd(trace);
      if (stats.available()) {
        found = stats;
      }
    }
    return found;
  }

  private static CaseObservation.RunStats lastRunEnd(Path trace) {
    CaseObservation.RunStats found = CaseObservation.RunStats.EMPTY;
    List<String> lines;
    try {
      lines = Files.readAllLines(trace, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return CaseObservation.RunStats.EMPTY;
    }
    for (String line : lines) {
      if (!line.contains("\"run_end\"")) {
        continue;
      }
      JsonNode node;
      try {
        node = Json.MAPPER.readTree(line);
      } catch (IOException e) {
        continue;
      }
      if (node == null || !"run_end".equals(node.path("event").asText())) {
        continue;
      }
      found =
          new CaseObservation.RunStats(
              node.path("tokens_in").asInt(0),
              node.path("tokens_out").asInt(0),
              node.path("latency_ms").asLong(0),
              true);
    }
    return found;
  }
}
