package org.cra.runner.cli;

import java.util.EnumSet;
import java.util.Set;
import org.cra.core.api.CrossCheckerOptions;

/** CLI 配置中的 CrossChecker 模式串解析（05§5，逗号分隔；空段忽略）。 */
final class CrossCheckerModeParser {

  private CrossCheckerModeParser() {}

  static Set<CrossCheckerOptions.Mode> parse(String modes) {
    Set<CrossCheckerOptions.Mode> out = EnumSet.noneOf(CrossCheckerOptions.Mode.class);
    for (String part : modes.split(",")) {
      if (!part.isBlank()) {
        out.add(CrossCheckerOptions.Mode.parse(part));
      }
    }
    return out;
  }
}
