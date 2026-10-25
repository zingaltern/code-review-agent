package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/**
 * {@code cra-eval} 参数解析（run | annotate 子命令，05§3）。缺参/非法值 → CraConfigException（退出码 2，与 cra review
 * 同语义）。
 */
public final class EvalCliArgs {

  /** run 命令参数。 */
  public record RunArgs(
      Path dataset,
      String set,
      int k,
      String depth,
      Path cliJar,
      Path workDir,
      Path jsonOut,
      long timeoutSec,
      List<String> onlyCases) {}

  /** annotate 命令参数。 */
  public record AnnotateArgs(
      Path fixture, String action, String toSet, String annotator, String reason) {}

  private EvalCliArgs() {}

  /**
   * 解析参数。
   *
   * @param args 命令行参数（首个为子命令）
   * @param env 环境变量（LLM 配置检测用）
   * @return 解析结果（RunArgs 或 AnnotateArgs）
   */
  public static Object parse(String[] args, Map<String, String> env) {
    if (args.length == 0) {
      throw new CraConfigException(usage());
    }
    String sub = args[0];
    Map<String, String> named = new HashMap<>();
    List<String> positional = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--")) {
        if (i + 1 >= args.length) {
          throw new CraConfigException("参数缺值: " + arg);
        }
        named.put(arg, args[++i]);
      } else {
        positional.add(arg);
      }
    }
    return switch (sub) {
      case "run" -> parseRun(named, env);
      case "annotate" -> parseAnnotate(named, positional);
      case "report" ->
          throw new CraConfigException(
              "cra-eval report 随 M3 处置采集落地（T3.4，06§1.1）；当前支持: run | annotate");
      default -> throw new CraConfigException("未知子命令: " + sub + "；" + usage());
    };
  }

  private static RunArgs parseRun(Map<String, String> named, Map<String, String> env) {
    String dataset = named.get("--dataset");
    if (dataset == null || dataset.isBlank()) {
      throw new CraConfigException("缺参数: --dataset <dir>");
    }
    Path datasetPath = Path.of(dataset);
    String set = named.getOrDefault("--set", "all");
    int k = parsePositive(named.get("--k"), "--k", 1);
    String depth = named.getOrDefault("--depth", "quick");
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（评测默认 quick，04§2.4）");
    }
    Path cliJar = locateCliJar(named.get("--cli-jar"));
    Path workDir = Path.of(named.getOrDefault("--work-dir", "cra-eval-work"));
    String jsonOut = named.get("--json");
    long timeout = parsePositive(named.get("--timeout-sec"), "--timeout-sec", 900);
    List<String> only =
        named.containsKey("--case") ? List.of(named.get("--case").split(",")) : List.of();
    if (!CliProcessRunner.llmConfigured(env)) {
      // 无真实 LLM 配置：run 仍可执行（LLM 步骤如实失败），报告显著声明——不静默伪装
    }
    return new RunArgs(
        datasetPath,
        set,
        k,
        depth,
        cliJar,
        workDir,
        jsonOut == null ? null : Path.of(jsonOut),
        timeout,
        only);
  }

  private static AnnotateArgs parseAnnotate(Map<String, String> named, List<String> positional) {
    String fixture = named.get("--fixture");
    if (fixture == null || fixture.isBlank()) {
      throw new CraConfigException("缺参数: --fixture <caseDir>");
    }
    String action =
        named.getOrDefault("--action", positional.isEmpty() ? "prefill" : positional.get(0));
    if (!"prefill".equals(action) && !"confirm".equals(action) && !"relabel".equals(action)) {
      throw new CraConfigException(
          "annotate action 非法: " + action + "（prefill=预填草稿 | confirm=人工确认后收尾 | relabel=改标复核通道）");
    }
    String toSet = named.get("--to-set");
    String annotator = named.getOrDefault("--annotator", "unknown");
    String reason = named.getOrDefault("--reason", "");
    if ("relabel".equals(action) && (toSet == null || toSet.isBlank())) {
      throw new CraConfigException("relabel 需要 --to-set bad|clean 与 --reason");
    }
    return new AnnotateArgs(Path.of(fixture), action, toSet, annotator, reason);
  }

  private static Path locateCliJar(String hint) {
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

  private static int parsePositive(String value, String flag, int dflt) {
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

  private static String usage() {
    return """
        用法:
          cra-eval run --dataset <dir> [--set all|bad|clean|adversarial|adv-injection|adv-hallucination|adv-refusal]
              [--k N] [--depth quick|standard] [--cli-jar PATH] [--work-dir DIR] [--json PATH]
              [--timeout-sec N] [--case ID[,ID...]]
          cra-eval annotate --fixture <caseDir> [--action prefill|confirm|relabel] [--to-set bad|clean]
              [--annotator NAME] [--reason TEXT]""";
  }
}
