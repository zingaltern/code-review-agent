package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/**
 * {@code cra-eval} 参数解析（run | annotate | verify 子命令，05§3）。缺参/非法值 → CraConfigException（退出码 2，与 cra
 * review 同语义）。
 */
public final class EvalCliArgs {

  /** 无值开关（出现即 true，不消费下一个参数）。 */
  private static final List<String> BOOLEAN_FLAGS =
      List.of("--fail-on-red", "--crosschecker-enabled");

  /**
   * run 命令参数。
   *
   * @param dataset 数据集根
   * @param set 集合过滤
   * @param k 每样本运行次数
   * @param depth 深度档位
   * @param cliJar 被测 CLI jar
   * @param workDir 工作目录
   * @param jsonOut JSON 报告输出路径（可空）
   * @param timeoutSec 单 run 超时
   * @param onlyCases 仅跑指定用例（空 = 全部）
   * @param tier 门禁档位 auto|l1|l2|l3（auto 按 k/depth 推导）
   * @param commit 产出报告的代码提交（L2 哈希校验绑定；缺省取 GITHUB_SHA）
   * @param failOnRed 门禁红时以退出码 5 结束（CI L1/L2 判定用）
   * @param sha256Out 报告哈希输出路径（L2 本地跑 + CI 校验，04§2.4）
   * @param crosscheckerEnabled M4f CrossChecker 是否启用（context 类是否计入门禁）
   */
  public record RunArgs(
      Path dataset,
      String set,
      int k,
      String depth,
      Path cliJar,
      Path workDir,
      Path jsonOut,
      long timeoutSec,
      List<String> onlyCases,
      String tier,
      String commit,
      boolean failOnRed,
      Path sha256Out,
      boolean crosscheckerEnabled) {}

  /** annotate 命令参数。 */
  public record AnnotateArgs(
      Path fixture, String action, String toSet, String annotator, String reason) {}

  /**
   * verify 命令参数（T3.3：CI 校验本地 L2 报告）。
   *
   * @param report 报告 JSON 路径
   * @param sha256 期望哈希（可与 sha256File 二选一/同时给）
   * @param sha256File 哈希文件路径
   * @param tier 要求档位（可空）
   * @param expectCommit 要求报告 commit（CI 传 PR head）
   */
  public record VerifyArgs(
      Path report, String sha256, Path sha256File, String tier, String expectCommit) {}

  /**
   * gate 命令参数（T3.1：对已有报告离线重判，不重跑 LLM）。
   *
   * @param report 报告 JSON 路径
   * @param tier 档位（可空 = 沿用报告 tier，再退化为按 k/depth 推导）
   * @param jsonOut 重判报告输出路径（可空）
   * @param sha256Out 重判报告哈希输出路径（可空）
   * @param crosscheckerEnabled M4f 是否启用（context 类是否计入判定）
   */
  public record GateArgs(
      Path report, String tier, Path jsonOut, Path sha256Out, boolean crosscheckerEnabled) {}

  private EvalCliArgs() {}

  /**
   * 解析参数。
   *
   * @param args 命令行参数（首个为子命令）
   * @param env 环境变量（LLM 配置检测用）
   * @return 解析结果（RunArgs / AnnotateArgs / VerifyArgs）
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
        if (BOOLEAN_FLAGS.contains(arg)) {
          named.put(arg, "true");
          continue;
        }
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
      case "verify" -> parseVerify(named);
      case "gate" -> parseGate(named);
      case "report" ->
          throw new CraConfigException(
              "cra-eval report 随 M3 处置采集落地（T3.4，06§1.1）；当前支持: run | annotate | verify");
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
    String tier = named.getOrDefault("--tier", "auto");
    if (!"auto".equals(tier) && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("--tier 非法: " + tier + "（auto|l1|l2|l3，04§2.4）");
    }
    String commit = named.get("--commit");
    if (commit == null || commit.isBlank()) {
      commit = env.get("GITHUB_SHA");
    }
    boolean failOnRed = "true".equals(named.get("--fail-on-red"));
    boolean crosschecker = "true".equals(named.get("--crosschecker-enabled"));
    String sha256Out = named.get("--sha256-out");
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
        only,
        tier,
        commit == null || commit.isBlank() ? null : commit,
        failOnRed,
        sha256Out == null ? null : Path.of(sha256Out),
        crosschecker);
  }

  private static VerifyArgs parseVerify(Map<String, String> named) {
    String report = named.get("--report");
    if (report == null || report.isBlank()) {
      throw new CraConfigException("缺参数: --report <json>（verify 校验本地 L2 报告，04§2.4）");
    }
    String sha256 = named.get("--sha256");
    String sha256File = named.get("--sha256-file");
    if ((sha256 == null || sha256.isBlank()) && (sha256File == null || sha256File.isBlank())) {
      throw new CraConfigException("缺参数: --sha256 <hex> 或 --sha256-file <path>（二选一）");
    }
    String tier = named.get("--tier");
    if (tier != null && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("verify --tier 非法: " + tier + "（l1|l2|l3）");
    }
    return new VerifyArgs(
        Path.of(report),
        sha256,
        sha256File == null ? null : Path.of(sha256File),
        tier,
        named.get("--expect-commit"));
  }

  private static GateArgs parseGate(Map<String, String> named) {
    String report = named.get("--report");
    if (report == null || report.isBlank()) {
      throw new CraConfigException("缺参数: --report <json>（gate 对已有报告离线重判，T3.1）");
    }
    String tier = named.get("--tier");
    if (tier != null && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("gate --tier 非法: " + tier + "（l1|l2|l3）");
    }
    String jsonOut = named.get("--json-out");
    String sha256Out = named.get("--sha256-out");
    return new GateArgs(
        Path.of(report),
        tier,
        jsonOut == null ? null : Path.of(jsonOut),
        sha256Out == null ? null : Path.of(sha256Out),
        "true".equals(named.get("--crosschecker-enabled")));
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
          cra-eval run --dataset <dir> [--set all|bad|clean|l2|adversarial|adv-injection|adv-hallucination|adv-refusal]
              [--k N] [--depth quick|standard] [--cli-jar PATH] [--work-dir DIR] [--json PATH]
              [--tier auto|l1|l2|l3] [--commit SHA] [--fail-on-red] [--sha256-out PATH]
              [--crosschecker-enabled] [--timeout-sec N] [--case ID[,ID...]]
          cra-eval annotate --fixture <caseDir> [--action prefill|confirm|relabel] [--to-set bad|clean]
              [--annotator NAME] [--reason TEXT]
          cra-eval verify --report <json> [--sha256 <hex>|--sha256-file <path>] [--tier l1|l2|l3]
              [--expect-commit SHA]
          cra-eval gate --report <json> [--tier l1|l2|l3] [--json-out PATH] [--sha256-out PATH]
              [--crosschecker-enabled]""";
  }
}
