package org.cra.runner.cli;

import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;

/**
 * `cra sweep` 参数解析（M4d 夜间存量评审，05§3/§2.5）。
 *
 * <p>扫描范围 = {@code --since}（默认 30d）与 {@code --max-prs}（默认 10）；深度默认 quick（夜间批量成本纪律，
 * 06§4）。仓库身份、token、LLM 配置与 review 形态同源（环境变量优先规则一致，05§6）。
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
    boolean crossChecker =
        Boolean.parseBoolean(env.getOrDefault("CRA_CROSSCHECKER_ENABLED", "false"));
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--dry-run" -> dryRun = true;
        case "--dry-run=true", "--dry-run=false" -> dryRun = arg.endsWith("true");
        case "--crosschecker" -> crossChecker = true;
        case "--crosschecker=true" -> crossChecker = true;
        case "--crosschecker=false" -> crossChecker = false;
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
        default -> throw new CraConfigException("未知参数: " + arg);
      }
    }
    String platform = PlatformArgs.platform(named, env);
    boolean gitlab = PlatformArgs.GITLAB.equals(platform);
    String repo = named.get("--repo");
    if (gitlab) {
      String project =
          PlatformArgs.firstNonBlank(
              named.get("--gitlab-project"),
              env.get("CRA_GITLAB_PROJECT"),
              env.get("CI_PROJECT_PATH"),
              repo);
      if (project != null) {
        repo = project;
      }
    } else if (repo == null || repo.isBlank()) {
      repo = env.get("GITHUB_REPOSITORY");
    }
    if (repo == null || repo.isBlank()) {
      throw new CraConfigException(
          gitlab
              ? "缺参数: --gitlab-project / CRA_GITLAB_PROJECT / CI_PROJECT_PATH（group/project）"
              : "缺参数: --repo（或 GITHUB_REPOSITORY）");
    }
    String[] ownerRepo = PlatformArgs.splitRepo(repo, gitlab);
    String token;
    if (gitlab) {
      if (named.containsKey("--token")) {
        throw new CraConfigException(
            "GitLab token 只从 CRA_GITLAB_TOKEN / CRA_GITLAB_TOKEN_FILE 读取（--token 仅 GitHub）");
      }
      token = PlatformArgs.gitlabToken(env);
    } else {
      token = named.get("--token");
      if (token == null || token.isBlank()) {
        token = env.get("GITHUB_TOKEN");
      }
    }
    if (token == null || token.isBlank()) {
      throw new CraConfigException(
          gitlab
              ? "CRA_GITLAB_TOKEN 未设置（或 CRA_GITLAB_TOKEN_FILE 指向密钥文件）"
              : "缺参数: --token（或 GITHUB_TOKEN）");
    }
    String llmApiKey = env.get("CRA_LLM_API_KEY");
    String llmBaseUrl = env.get("CRA_LLM_BASE_URL");
    String llmModel = env.get("CRA_LLM_MODEL");
    if (llmApiKey == null || llmApiKey.isBlank()) {
      throw new CraConfigException("CRA_LLM_API_KEY 未设置（密钥只从环境变量读，05§6）");
    }
    if (llmBaseUrl == null || llmBaseUrl.isBlank() || llmModel == null || llmModel.isBlank()) {
      throw new CraConfigException("CRA_LLM_BASE_URL / CRA_LLM_MODEL 未设置");
    }
    String depth = named.getOrDefault("--depth", "quick");
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（quick|standard|deep）");
    }
    int maxPrs = positiveInt("--max-prs", named.getOrDefault("--max-prs", "10"));
    String since = named.getOrDefault("--since", "30d");
    SweepScope.parseSince(since); // 早失败：非法区间在组装前拒绝（退出码 2）
    int historyWeeks =
        positiveInt(
            "--crosschecker-history-weeks",
            named.getOrDefault(
                "--crosschecker-history-weeks",
                env.getOrDefault("CRA_CROSSCHECKER_HISTORY_WEEKS", "8")));
    String modes =
        named.getOrDefault(
            "--crosschecker-modes", env.getOrDefault("CRA_CROSSCHECKER_MODES", "assumption,sniff"));
    String temperature = named.getOrDefault("--llm-temperature", env.get("CRA_LLM_TEMPERATURE"));
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
            llmApiKey,
            llmBaseUrl,
            llmModel,
            named.getOrDefault("--run-root", ".cra/runs"),
            named.getOrDefault("--workspace", "."),
            2000,
            8000,
            3,
            500_000L,
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
            blankToNull(temperature),
            CraConfig.DEFAULT_PUSH_LOOKBACK,
            ReviewMode.ISSUE,
            SignoffConfig.defaults(),
            platform,
            gitlab ? PlatformArgs.gitlabSettings(named, env) : GitLabSettings.none(),
            SemanticContextConfig.defaults());
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
