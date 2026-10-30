package org.cra.runner.cli;

import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;

/**
 * `cra sweep` 参数解析（M4d 夜间存量评审，05§3/§2.5）。
 *
 * <p>扫描范围 = {@code --since}（默认 30d）与 {@code --max-prs}（默认 10）；深度默认 quick（夜间批量成本纪律，
 * 06§4）。仓库身份、token、LLM 配置与 review 形态同源（环境变量优先规则一致，05§6）—— 共享解析在 {@link PlatformArgs}（R5：与 CliArgs
 * 同语义收敛）；tri-state 旗标（--dry-run/--crosschecker）取值 = env 先行、显式 flag 覆盖，与 review 的 seen 语义净效果一致。
 */
final class SweepArgs {

  private SweepArgs() {}

  /**
   * 解析 sweep 参数。
   *
   * @param args 命令行参数（"sweep" 子命令之后）
   * @param env 环境变量视图（测试注入）
   * @return 运行配置 + 扫描范围
   */
  static Parsed parse(String[] args, Map<String, String> env) {
    Map<String, String> named = new HashMap<>();
    boolean dryRun = false;
    boolean crossChecker = Boolean.parseBoolean(envOr(env, "CRA_CROSSCHECKER_ENABLED", "false"));
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      PlatformArgs.TriState dryRunFlag = PlatformArgs.matchTriState(arg, "--dry-run");
      if (dryRunFlag != null) {
        dryRun = dryRunFlag.value();
        continue;
      }
      PlatformArgs.TriState crossCheckerFlag = PlatformArgs.matchTriState(arg, "--crosschecker");
      if (crossCheckerFlag != null) {
        crossChecker = crossCheckerFlag.value();
        continue;
      }
      switch (arg) {
        case "--repo",
            "--since",
            "--max-prs",
            "--depth",
            "--token",
            "--platform",
            "--github-api",
            "--gitlab-base-url",
            "--gitlab-project",
            "--run-root",
            "--workspace",
            "--crosschecker-modes",
            "--crosschecker-history-weeks",
            "--llm-temperature" -> {
          if (i + 1 >= args.length) {
            throw new CraConfigException("参数缺值: " + arg);
          }
          named.put(arg, args[++i]);
        }
        default -> {
          int eq = arg.indexOf('=');
          throw new CraConfigException("未知参数: " + (eq > 0 ? arg.substring(0, eq) : arg));
        }
      }
    }
    String platform = PlatformArgs.platform(named, env);
    boolean gitlab = PlatformArgs.GITLAB.equals(platform);
    String[] ownerRepo = PlatformArgs.requireOwnerRepo(named, env, gitlab);
    String token = PlatformArgs.tokenOrGitlabGuard(named, env, gitlab);
    if (token == null || token.isBlank()) {
      throw new CraConfigException(
          gitlab
              ? "CRA_GITLAB_TOKEN 未设置（或 CRA_GITLAB_TOKEN_FILE 指向密钥文件）"
              : "缺参数: --token（或 GITHUB_TOKEN）");
    }
    PlatformArgs.LlmEnvTriple llm = PlatformArgs.llmEnvTriple(env, true);
    String depth = PlatformArgs.depthCheck(named.getOrDefault("--depth", "quick"));
    int maxPrs = positiveInt("--max-prs", named.getOrDefault("--max-prs", "10"));
    String since = named.getOrDefault("--since", "30d");
    SweepScope.parseSince(since); // 早失败：非法区间在组装前拒绝（退出码 2）
    int historyWeeks =
        positiveInt(
            "--crosschecker-history-weeks",
            named.getOrDefault(
                "--crosschecker-history-weeks", envOr(env, "CRA_CROSSCHECKER_HISTORY_WEEKS", "8")));
    String modes = PlatformArgs.crosscheckerModes(named, env);
    // flag 优先、env 次之；空白值（含 GitHub Actions 空 input ""）等同未配置 = 不传温度
    String temperature =
        blankToNull(
            named.containsKey("--llm-temperature")
                ? named.get("--llm-temperature")
                : env.get("CRA_LLM_TEMPERATURE"));
    CraConfig config =
        new CraConfig(
            ownerRepo[0],
            ownerRepo[1],
            0,
            "",
            "",
            depth,
            dryRun,
            false,
            token,
            named.getOrDefault("--github-api", "https://api.github.com"),
            llm.apiKey(),
            llm.baseUrl(),
            llm.model(),
            named.getOrDefault("--run-root", ".cra/runs"),
            named.getOrDefault("--workspace", "."),
            org.cra.core.api.PipelineOptions.DEFAULT_LARGE_DIFF_LINES,
            org.cra.core.api.PipelineOptions.DEFAULT_HARD_CAP_LINES,
            3,
            org.cra.core.api.PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS,
            0.70,
            0.85,
            "never",
            20,
            false,
            CliArgs.DEFAULT_EXEC,
            "~/.cra/ledger.db",
            blankToNull(named.getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG"))),
            blankToNull(named.getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT"))),
            env.getOrDefault("CRA_LLM_FALLBACK_CHAIN", "standard,light"),
            env.getOrDefault("CRA_LLM_RPM", ""),
            crossChecker,
            modes,
            historyWeeks,
            temperature,
            CraConfig.DEFAULT_PUSH_LOOKBACK,
            ReviewMode.ISSUE,
            SignoffConfig.defaults(),
            platform,
            gitlab ? PlatformArgs.gitlabSettings(named, env) : GitLabSettings.none(),
            SemanticContextConfig.defaults(),
            // 02§4.4：测试文件默认不评审；sweep 无独立 flag，沿用同一环境变量开关
            Boolean.parseBoolean(envOr(env, "CRA_REVIEW_TESTS", "false")),
            CliArgs.agentBudgets(named, env));
    return new Parsed(config, since, maxPrs);
  }

  private static int positiveInt(String name, String value) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(name + " 必须为正整数: " + value);
    }
  }

  /**
   * 解析结果。
   *
   * @param config 运行配置（mode=ISSUE，pr/base/head 为占位——每 PR 请求逐一填充）
   * @param since 扫描窗口（形如 7d / 12h / 2w）
   * @param maxPrs 最大评审 PR 数
   */
  record Parsed(CraConfig config, String since, int maxPrs) {}

  /** 环境变量读取：空白值等同未设置（GitHub Actions 空 input 是 ""）。 */
  private static String envOr(Map<String, String> env, String key, String defaultValue) {
    String value = env.get(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  /** 空白值归一为 null（与 CliArgs 同语义：空白 = 未配置，原值不剥空白）。 */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
