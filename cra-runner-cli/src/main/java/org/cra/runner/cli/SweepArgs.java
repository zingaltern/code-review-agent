package org.cra.runner.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;
import org.cra.core.api.PipelineOptions;
import org.cra.llm.api.LlmParsing;

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
    // CRA_LLM_MAX_CONCURRENCY 与 review 同源（CI 配置面，只走环境变量）
    int maxConcurrency =
        LlmParsing.parseMaxConcurrency(
            PlatformArgs.blankToNull(env.get("CRA_LLM_MAX_CONCURRENCY")),
            "CRA_LLM_MAX_CONCURRENCY");
    // 05§5 review.* 策略组（review/sweep/server 共用同一 CRA_REVIEW_* 环境变量；sweep 面向 CI，不新增 flag）
    PipelineOptions defaults = PipelineOptions.defaults();
    PlatformArgs.DegradeAt degradeAt =
        PlatformArgs.degradeAt(
            "CRA_REVIEW_DEGRADE_AT",
            PlatformArgs.blankToNull(env.get("CRA_REVIEW_DEGRADE_AT")),
            new PlatformArgs.DegradeAt(defaults.degradeAtFirst(), defaults.degradeAtSecond()));
    int largeDiffLines =
        PlatformArgs.positiveInt(
            "CRA_REVIEW_LARGE_DIFF_LINES",
            PlatformArgs.blankToNull(env.get("CRA_REVIEW_LARGE_DIFF_LINES")),
            defaults.largeDiffLines());
    int hardCapLines =
        PlatformArgs.positiveInt(
            "CRA_REVIEW_HARD_CAP_LINES",
            PlatformArgs.blankToNull(env.get("CRA_REVIEW_HARD_CAP_LINES")),
            defaults.hardCapLines());
    if (hardCapLines <= largeDiffLines) {
      throw new CraConfigException(
          "CRA_REVIEW_HARD_CAP_LINES 必须大于 CRA_REVIEW_LARGE_DIFF_LINES: "
              + hardCapLines
              + " <= "
              + largeDiffLines);
    }
    CraConfig config =
        CraConfig.builder()
            .repoOwner(ownerRepo.owner())
            .repoName(ownerRepo.name())
            .prNumber(0)
            .baseSha("")
            .headSha("")
            .depth(depth)
            .dryRun(scan.dryRun())
            .securityBlock(false)
            // GitLab 模式下 token 只属于 GitLabSettings（与 CliArgs 同一密钥隔离不变量，ADR-0013）
            .githubToken(gitlab ? null : token)
            .githubApi(
                scan.named().getOrDefault("--github-api", PlatformArgs.DEFAULT_GITHUB_API_BASE))
            .llmApiKey(llm.apiKey())
            .llmBaseUrl(llm.baseUrl())
            .llmModel(llm.model())
            .runRoot(scan.named().getOrDefault("--run-root", CraConfig.DEFAULT_RUN_ROOT))
            .workspace(scan.named().getOrDefault("--workspace", "."))
            .largeDiffLines(largeDiffLines)
            .hardCapLines(hardCapLines)
            .nitBudget(
                PlatformArgs.nitBudget(
                    "CRA_REVIEW_NIT_BUDGET",
                    PlatformArgs.blankToNull(env.get("CRA_REVIEW_NIT_BUDGET")),
                    defaults.nitBudgetDefault()))
            .runTotalTokens(defaults.runTotalTokens())
            .degradeAtFirst(degradeAt.first())
            .degradeAtSecond(degradeAt.second())
            .publishUnverified(
                PlatformArgs.publishUnverified(
                    "CRA_REVIEW_PUBLISH_UNVERIFIED",
                    PlatformArgs.blankToNull(env.get("CRA_REVIEW_PUBLISH_UNVERIFIED")),
                    defaults.publishUnverified()))
            .spotCheckPercent(
                PlatformArgs.percent(
                    "CRA_REVIEW_SPOT_CHECK_PERCENT",
                    PlatformArgs.blankToNull(env.get("CRA_REVIEW_SPOT_CHECK_PERCENT")),
                    defaults.importantRefutedSpotCheckPercent()))
            .local(false)
            .execCommand(CliArgs.DEFAULT_EXEC)
            .ledgerDb(CraConfig.DEFAULT_LEDGER_DB)
            .llmModelStrong(
                PlatformArgs.blankToNull(
                    scan.named().getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG"))))
            .llmModelLight(
                PlatformArgs.blankToNull(
                    scan.named().getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT"))))
            .fallbackChain(
                PlatformArgs.envOr(env, "CRA_LLM_FALLBACK_CHAIN", CraConfig.DEFAULT_FALLBACK_CHAIN))
            .rpm(PlatformArgs.envOr(env, "CRA_LLM_RPM", ""))
            .llmMaxConcurrency(maxConcurrency)
            .crossCheckerEnabled(scan.crossChecker())
            .crossCheckerModes(modes)
            .crossCheckerHistoryWeeks(historyWeeks)
            .llmTemperature(temperature)
            .pushLookback(CraConfig.DEFAULT_PUSH_LOOKBACK)
            .reviewMode(ReviewMode.ISSUE)
            .signoff(SignoffConfig.defaults())
            .platform(platform)
            .gitlab(gitlab ? PlatformArgs.gitlabSettings(scan.named(), env) : GitLabSettings.none())
            .semanticContext(SemanticContextConfig.defaults())
            // 02§4.4：测试文件默认不评审；sweep 无独立 flag，沿用同一环境变量开关
            .includeTestFiles(
                PlatformArgs.bool(
                    "CRA_REVIEW_TESTS",
                    PlatformArgs.blankToNull(env.get("CRA_REVIEW_TESTS")),
                    defaults.includeTestFiles()))
            .agentBudgets(CliArgs.agentBudgets(scan.named(), env))
            .build();
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
