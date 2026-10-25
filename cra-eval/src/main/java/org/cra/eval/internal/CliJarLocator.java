package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.exception.CraConfigException;

/** 被测 CLI jar 定位（--cli-jar 优先，否则在 reactor target 目录查找）；属于构建环境解析，不参与参数语义。 */
final class CliJarLocator {

  private CliJarLocator() {}

  static Path locate(String hint) {
    if (hint != null && !hint.isBlank()) {
      Path p = Path.of(hint);
      if (!Files.isRegularFile(p)) {
        throw new CraConfigException("--cli-jar 不存在: " + p);
      }
      return p.toAbsolutePath();
    }
    List<Path> candidates = new ArrayList<>();
    Path cwd = Path.of("").toAbsolutePath();
    candidates.add(cwd.resolve("cra-runner-cli/target"));
    candidates.add(
        cwd.getParent() == null ? cwd : cwd.getParent().resolve("cra-runner-cli/target"));
    for (Path dir : candidates) {
      if (Files.isDirectory(dir)) {
        try (var stream = Files.list(dir)) {
          var found =
              stream
                  .filter(p -> p.getFileName().toString().startsWith("cra-runner-cli-"))
                  .filter(p -> p.getFileName().toString().endsWith(".jar"))
                  .filter(p -> !p.getFileName().toString().endsWith("sources.jar"))
                  .sorted()
                  .findFirst();
          if (found.isPresent()) {
            return found.get().toAbsolutePath();
          }
        } catch (java.io.IOException e) {
          throw new CraConfigException("CLI jar 搜索失败: " + dir + "（" + e.getMessage() + "）");
        }
      }
    }
    throw new CraConfigException(
        "未找到被测 CLI jar（cra-runner-cli/target/cra-runner-cli-*.jar）——先构建: "
            + "mvn -B -ntp -pl cra-runner-cli -am package -DskipTests，或用 --cli-jar 指定");
  }
}
