package org.cra.runner.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;
import org.cra.core.api.PipelineOptions;

/**
 * `cra sweep` 参数解析（M4d 夜间存量评审，05§3/§2.5）。
 *
 * <p>扫描范围 = {@code --since}（默认 30d）与 {@code --max-prs}（默认 10）；深度默认 quick（夜间批量成本纪律，
 * 06§4）。仓库身份、token、LLM 配置与 review 形态同源（环境变量优先规则一致，05§6）—— 共享解析在 {@link PlatformArgs}（R5：与 CliArgs
 * 同语义收敛）；tri-state 旗标（--dry-run/--crosschecker）中仅 --crosschecker 有环境变量回退，显式 flag 覆盖 env。
 */
final class SweepArgs {

  /** 带值参数（flag 后紧跟一个值）。 */
  private static final Set<String> VALUE_FLAGS =
      Set.of(
          "--repo",
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
          "--llm-temperature");

  private SweepArgs() {}

  /**
   * 解析 sweep 参数。
   *
   * @param args 命令行参数（"sweep" 子命令之后）
   * @param env 环境变量视图（测试注入）
   * @return 运行配置 + 扫描范围
   */
  static Parsed parse(String[] args, Map<String, String> env) {
    Scan scan = scan(args, env);
    String platform = PlatformArgs.platform(scan.named(), env);
    boolean gitlab = PlatformArgs.GITLAB.equals(platform);
    PlatformArgs.OwnerRepo ownerRepo = PlatformArgs.requireOwnerRepo(scan.named(), env, gitlab);
    String token = requireToken(PlatformArgs.tokenOrGitlabGuard(scan.named(), env, gitlab), gitlab);
    PlatformArgs.LlmEnvTriple llm = PlatformArgs.llmEnvTriple(env, true);
    String depth = PlatformArgs.depthCheck(scan.named().getOrDefault("--depth", "quick"));
    int maxPrs =
        PlatformArgs.positiveInt("--max-prs", scan.named().getOrDefault("--max-prs", "10"), 10);
    String since = scan.named().getOrDefault("--since", "30d");
    SweepScope.parseSince(since); // 早失败：非法区间在组装前拒绝（退出码 2）
    int historyWeeks =
        PlatformArgs.positiveInt(
            "--crosschecker-history-weeks",
            PlatformArgs.firstNonBlank(
                scan.named().get("--crosschecker-history-weeks"),
                PlatformArgs.envOr(env, "CRA_CROSSCHECKER_HISTORY_WEEKS", "8")),
            8);
    String modes = PlatformArgs.crosscheckerModes(scan.named(), env);
    String temperature = temperature(scan.named(), env);
    PipelineOptions defaults = PipelineOptions.defaults();
    CraConfig config =
        new CraConfig(
            ownerRepo.owner(),
            ownerRepo.name(),
            0,
            "",
            "",
            depth,
            scan.dryRun(),
            false,
            // GitLab 模式下 token 只属于 GitLabSettings（与 CliArgs 同一密钥隔离不变量，ADR-0013）
            gitlab ? null : token,
            scan.named().getOrDefault("--github-api", PlatformArgs.DEFAULT_GITHUB_API_BASE),
            llm.apiKey(),
            llm.baseUrl(),
            llm.model(),
            scan.named().getOrDefault("--run-root", CraConfig.DEFAULT_RUN_ROOT),
            scan.named().getOrDefault("--workspace", "."),
            defaults.largeDiffLines(),
            defaults.hardCapLines(),
            defaults.nitBudgetDefault(),
            defaults.runTotalTokens(),
            defaults.degradeAtFirst(),
            defaults.degradeAtSecond(),
            defaults.publishUnverified(),
            defaults.importantRefutedSpotCheckPercent(),
            false,
            CliArgs.DEFAULT_EXEC,
            CraConfig.DEFAULT_LEDGER_DB,
            PlatformArgs.blankToNull(
                scan.named().getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG"))),
            PlatformArgs.blankToNull(
                scan.named().getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT"))),
            PlatformArgs.envOr(env, "CRA_LLM_FALLBACK_CHAIN", CraConfig.DEFAULT_FALLBACK_CHAIN),
            PlatformArgs.envOr(env, "CRA_LLM_RPM", ""),
            scan.crossChecker(),
            modes,
            historyWeeks,
            temperature,
            CraConfig.DEFAULT_PUSH_LOOKBACK,
            ReviewMode.ISSUE,
            SignoffConfig.defaults(),
            platform,
            gitlab ? PlatformArgs.gitlabSettings(scan.named(), env) : GitLabSettings.none(),
            SemanticContextConfig.defaults(),
            // 02§4.4：测试文件默认不评审；sweep 无独立 flag，沿用同一环境变量开关
            Boolean.parseBoolean(PlatformArgs.envOr(env, "CRA_REVIEW_TESTS", "false")),
            CliArgs.agentBudgets(scan.named(), env));
    return new Parsed(config, since, maxPrs);
  }

  /** flag 扫描结果（值参数 + 两个布尔开关）。 */
  private record Scan(Map<String, String> named, boolean dryRun, boolean crossChecker) {}

  private static Scan scan(String[] args, Map<String, String> env) {
    Map<String, String> named = new HashMap<>();
    boolean dryRun = false;
    boolean crossChecker =
        Boolean.parseBoolean(PlatformArgs.envOr(env, "CRA_CROSSCHECKER_ENABLED", "false"));
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
      if (VALUE_FLAGS.contains(arg)) {
        if (i + 1 >= args.length) {
          throw new CraConfigException("参数缺值: " + arg);
        }
        named.put(arg, args[++i]);
        continue;
      }
      int eq = arg.indexOf('=');
      throw new CraConfigException("未知参数: " + (eq > 0 ? arg.substring(0, eq) : arg));
    }
    return new Scan(named, dryRun, crossChecker);
  }

  private static String requireToken(String token, boolean gitlab) {
    if (token == null || token.isBlank()) {
      throw new CraConfigException(
          gitlab
              ? "CRA_GITLAB_TOKEN 未设置（或 CRA_GITLAB_TOKEN_FILE 指向密钥文件）"
              : "缺参数: --token（或 GITHUB_TOKEN）");
    }
    return token;
  }

  /** flag 优先、env 次之；空白值（含 GitHub Actions 空 input ""）等同未配置 = 不传温度。 */
  private static String temperature(Map<String, String> named, Map<String, String> env) {
    return PlatformArgs.blankToNull(
        named.containsKey("--llm-temperature")
            ? named.get("--llm-temperature")
            : env.get("CRA_LLM_TEMPERATURE"));
  }

  /**
   * 解析结果。
   *
   * @param config 运行配置（mode=ISSUE，pr/base/head 为占位——每 PR 请求逐一填充）
   * @param since 扫描窗口（形如 7d / 12h / 2w）
   * @param maxPrs 最大评审 PR 数
   */
  record Parsed(CraConfig config, String since, int maxPrs) {}
}
