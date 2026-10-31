package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval badcase add} 参数解析（T3.5）。 */
final class BadcaseArgsParser {

  /** badcase 接受的全部旗标（与 EvalUsage 同步）。 */
  private static final List<String> FLAGS =
      List.of(
          "--label",
          "--dataset",
          "--to-set",
          "--case-id",
          "--annotator",
          "--reason",
          "--diff-file",
          "--snapshot-dir",
          "--artifact-root",
          "--token",
          "--api-base",
          "--file",
          "--line");

  private BadcaseArgsParser() {}

  static EvalCliArgs.BadcaseArgs parse(EvalArgOptions options, Map<String, String> env) {
    options.rejectUnknownFlags(FLAGS);
    // 位置参数合法：add <run_ref> <finding_id>
    Map<String, String> named = options.named();
    List<String> positional = options.positional();
    if (positional.isEmpty() || !"add".equals(positional.get(0))) {
      throw new CraConfigException("badcase 子命令当前只支持 add；" + EvalUsage.USAGE_TEXT);
    }
    if (positional.size() < 3) {
      throw new CraConfigException(
          "缺参数: badcase add <run_ref> <finding_id> --label missed|false-positive|relabeled --dataset <dir>");
    }
    String dataset = named.get("--dataset");
    if (dataset == null || dataset.isBlank()) {
      throw new CraConfigException("缺参数: --dataset <dir>");
    }
    String label = named.get("--label");
    if (label == null || label.isBlank()) {
      throw new CraConfigException("缺参数: --label missed|false-positive|relabeled");
    }
    if (!List.of("missed", "false-positive", "relabeled").contains(label)) {
      throw new CraConfigException("--label 非法: " + label + "（missed|false-positive|relabeled）");
    }
    Integer line = null;
    if (named.containsKey("--line")) {
      try {
        line = Integer.parseInt(named.get("--line"));
      } catch (NumberFormatException e) {
        throw new CraConfigException("--line 必须为正整数: " + named.get("--line"));
      }
      if (line <= 0) {
        throw new CraConfigException("--line 必须为正整数: " + named.get("--line"));
      }
    }
    String apiBase = named.get("--api-base");
    if (apiBase == null || apiBase.isBlank()) {
      apiBase = env.getOrDefault("GITHUB_API_URL", "https://api.github.com");
    }
    return new EvalCliArgs.BadcaseArgs(
        Path.of(dataset),
        positional.get(1),
        positional.get(2),
        label,
        named.get("--to-set"),
        named.get("--case-id"),
        named.getOrDefault("--annotator", "unknown"),
        named.getOrDefault("--reason", ""),
        named.containsKey("--diff-file") ? Path.of(named.get("--diff-file")) : null,
        named.containsKey("--snapshot-dir") ? Path.of(named.get("--snapshot-dir")) : null,
        named.containsKey("--artifact-root") ? Path.of(named.get("--artifact-root")) : null,
        named.get("--token"),
        apiBase,
        named.get("--file"),
        line);
  }
}
