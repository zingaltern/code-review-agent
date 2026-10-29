package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval annotate} 参数解析（T3.5 / 06§2）。 */
final class AnnotateArgsParser {

  /** annotate 接受的全部旗标（与 EvalUsage 同步）。 */
  private static final List<String> FLAGS =
      List.of("--fixture", "--action", "--to-set", "--annotator", "--reason");

  private AnnotateArgsParser() {}

  static EvalCliArgs.AnnotateArgs parse(EvalArgOptions options) {
    options.rejectUnknownFlags(FLAGS);
    // 位置参数合法：第一个位置参数可作 action 缺省（annotate --fixture x confirm）
    String fixture = options.named().get("--fixture");
    if (fixture == null || fixture.isBlank()) {
      throw new CraConfigException("缺参数: --fixture <caseDir>");
    }
    String action =
        options
            .named()
            .getOrDefault(
                "--action",
                options.positional().isEmpty() ? "prefill" : options.positional().get(0));
    if (!"prefill".equals(action) && !"confirm".equals(action) && !"relabel".equals(action)) {
      throw new CraConfigException(
          "annotate action 非法: " + action + "（prefill=预填草稿 | confirm=人工确认后收尾 | relabel=改标复核通道）");
    }
    String toSet = options.named().get("--to-set");
    String annotator = options.named().getOrDefault("--annotator", "unknown");
    String reason = options.named().getOrDefault("--reason", "");
    if ("relabel".equals(action) && (toSet == null || toSet.isBlank())) {
      throw new CraConfigException("relabel 需要 --to-set bad|clean 与 --reason");
    }
    return new EvalCliArgs.AnnotateArgs(Path.of(fixture), action, toSet, annotator, reason);
  }
}
