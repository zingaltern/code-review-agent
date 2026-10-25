package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval run} 参数解析（05§3）。 */
final class RunArgsParser {

  private RunArgsParser() {}

  static EvalCliArgs.RunArgs parse(EvalArgOptions options, Map<String, String> env) {
    Map<String, String> named = options.named();
    String dataset = named.get("--dataset");
    if (dataset == null || dataset.isBlank()) {
      throw new CraConfigException("缺参数: --dataset <dir>");
    }
    Path datasetPath = Path.of(dataset);
    String set = named.getOrDefault("--set", "all");
    int k = EvalArgsSupport.parsePositive(named.get("--k"), "--k", 1);
    String depth = named.getOrDefault("--depth", "quick");
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（评测默认 quick，04§2.4）");
    }
    Path cliJar = CliJarLocator.locate(named.get("--cli-jar"));
    Path workDir = Path.of(named.getOrDefault("--work-dir", "cra-eval-work"));
    String jsonOut = named.get("--json");
    long timeout = EvalArgsSupport.parsePositive(named.get("--timeout-sec"), "--timeout-sec", 900);
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
    return new EvalCliArgs.RunArgs(
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
}
