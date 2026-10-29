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
 * <run_id>/trace.ndjson}。多 run 目录时按目录名字典序取最新（run_id 时间戳零填充，字典序=时间序）；只看最新 trace，其无有效
 * run_end（含损坏/统计不全）即不可用，不回退旧 trace 掩盖读失败。缺失/不可读时返回 {@link
 * org.cra.eval.internal.CaseObservation.RunStats#EMPTY}，观测进报告但不进 token/耗时均值分母（报告如实标注分母）。
 */
public final class RunStatsReader {

  private RunStatsReader() {}

  /**
   * 读取一次 run 的统计。
   *
   * @param runRoot 该次 run 的 trace 根目录（{@code <workDir>/run-<k>}）
   * @return 最新 trace 的最后一条 run_end（缺失/损坏/统计不全为 EMPTY）
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
              .toList();
    } catch (IOException e) {
      return CaseObservation.RunStats.EMPTY;
    }
    if (traces.isEmpty()) {
      return CaseObservation.RunStats.EMPTY;
    }
    // 多 run 目录：目录名字典序最大即最新；只读最新 trace——损坏时不回退旧 trace 掩盖读失败
    Path latest =
        traces.stream()
            .max(
                Comparator.comparing((Path p) -> p.getParent().getFileName().toString())
                    .thenComparing(Path::toString))
            .orElseThrow();
    return lastRunEnd(latest);
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
      JsonNode tokensIn = node.path("tokens_in");
      JsonNode tokensOut = node.path("tokens_out");
      JsonNode latencyMs = node.path("latency_ms");
      if (tokensIn.isMissingNode()
          || tokensIn.isNull()
          || tokensOut.isMissingNode()
          || tokensOut.isNull()
          || latencyMs.isMissingNode()
          || latencyMs.isNull()) {
        // run_end 存在但统计不全：宁可不可用，也不以 0 值进均值分母（掩盖采集缺陷）
        found = CaseObservation.RunStats.EMPTY;
        continue;
      }
      found =
          new CaseObservation.RunStats(
              tokensIn.asInt(), tokensOut.asInt(), latencyMs.asLong(), true);
    }
    return found;
  }
}
