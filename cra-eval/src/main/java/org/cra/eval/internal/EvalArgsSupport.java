package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.cra.common.exception.CraConfigException;

/** 参数解析共用的小工具（默认数据集、逗号路径列表、正整数校验）。 */
final class EvalArgsSupport {

  private EvalArgsSupport() {}

  static Path defaultDataset() {
    for (Path candidate : List.of(Path.of("cra-eval", "dataset"), Path.of("dataset"))) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  static List<Path> pathList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .filter(part -> !part.isBlank())
        .map(part -> Path.of(part.strip()))
        .toList();
  }

  static int parsePositive(String value, String flag, int dflt) {
    if (value == null || value.isBlank()) {
      return dflt;
    }
    try {
      int v = Integer.parseInt(value);
      if (v <= 0) {
        throw new NumberFormatException();
      }
      return v;
    } catch (NumberFormatException e) {
      throw new CraConfigException(flag + " 必须为正整数: " + value);
    }
  }
}
