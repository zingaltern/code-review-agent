package org.cra.eval.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.cra.common.exception.CraIntegrationException;

/**
 * 已固化 badcase 索引（T3.5）：扫描数据集目录下的 {@code <set>/<case>/badcase.json}，为周报提供"事后推翻 REFUTED"的拒真池样本（06§2 →
 * 06§1.1）。
 */
final class BadcaseIndex {

  private BadcaseIndex() {}

  /**
   * 扫描数据集根下的 badcase 来源文件。
   *
   * @param datasetRoot 数据集根（不存在时返回空）
   * @return 来源事实
   */
  static List<WindowData.BadcaseFacts> scan(Path datasetRoot) {
    if (datasetRoot == null || !Files.isDirectory(datasetRoot)) {
      return List.of();
    }
    List<Path> files;
    try (Stream<Path> walk = Files.walk(datasetRoot, 3)) {
      files =
          walk.filter(p -> p.getFileName().toString().equals("badcase.json"))
              .filter(Files::isRegularFile)
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new CraIntegrationException("badcase 索引扫描失败: " + datasetRoot, e);
    }
    List<WindowData.BadcaseFacts> out = new ArrayList<>();
    for (Path file : files) {
      JsonNode root;
      try {
        root = Json.MAPPER.readTree(file.toFile());
      } catch (IOException e) {
        throw new CraIntegrationException(
            "badcase.json 解析失败: " + file + "（" + e.getMessage() + "）", e);
      }
      out.add(
          new WindowData.BadcaseFacts(
              root.path("label").asText(""),
              root.path("run_id").asText(""),
              root.path("finding_id").asText(""),
              root.path("verifier_verdict").asText(""),
              root.path("added_at").asText("")));
    }
    return out;
  }
}
