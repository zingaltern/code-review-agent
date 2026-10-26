package org.cra.runner.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/**
 * `cra review` 参数解析（05§3 CLI 契约）。缺参/非法值 → CraConfigException（退出码 2）。 参数优先于环境变量；密钥类只认环境变量来源的传递（CI 中经
 * --token 传入亦视为环境侧提供）。
 *
 * <p>本地 pre-PR 模式（--local，T2.10）：--repo/--pr/--head/--token 与 CRA_LLM_* 全部转为可选—— 仓库身份由 remote
 * origin 推导、head 解析为 HEAD、无密钥时评审段如实跳过；dry-run 默认 true（05§3）。
 */
final class CliArgs {

  /** 本地实跑命令默认值（07 T2.10：默认 mvn -B -ntp verify，docs/00 §4 自检即现成命令）。 */
  static final String DEFAULT_EXEC = "mvn -B -ntp verify";

  private CliArgs() {}

  /**
   * 解析命令行参数与环境变量。
   *
   * @param args 命令行参数（review 子命令之后）
   * @param env 环境变量视图（测试注入）
   * @return 运行配置
   */
  static CraConfig parse(String[] args, Map<String, String> env) {
    // java -jar cra-runner-cli.jar review --repo …（05§3/05§4）：可选 review 子命令前缀
    if (args.length > 0 && "review".equals(args[0])) {
      args = java.util.Arrays.copyOfRange(args, 1, args.length);
    }
    Map<String, String> named = new HashMap<>();
    boolean dryRunFlag = false;
    boolean dryRunArgSeen = false;
    boolean securityBlock = false;
    boolean local = false;
    boolean crossChecker = false;
    boolean crossCheckerSeen = false;
    boolean signoff = false;
    boolean signoffSeen = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--dry-run" -> {
          dryRunFlag = true;
          dryRunArgSeen = true;
        }
        case "--dry-run=true", "--dry-run=false" -> {
          dryRunFlag = arg.endsWith("true");
          dryRunArgSeen = true;
        }
        case "--security-block" -> securityBlock = true;
        case "--local" -> local = true;
        case "--crosschecker" -> {
          crossChecker = true;
          crossCheckerSeen = true;
        }
        case "--crosschecker=true" -> {
          crossChecker = true;
          crossCheckerSeen = true;
        }
        case "--crosschecker=false" -> {
          crossChecker = false;
          crossCheckerSeen = true;
        }
        case "--signoff" -> {
          signoff = true;
          signoffSeen = true;
        }
        case "--signoff=true" -> {
          signoff = true;
          signoffSeen = true;
        }
        case "--signoff=false" -> {
          signoff = false;
          signoffSeen = true;
        }
        case "--repo",
            "--pr",
            "--base",
            "--head",
            "--depth",
            "--token",
            "--github-api",
            "--run-root",
            "--workspace",
            "--large-diff-lines",
            "--hard-cap-lines",
            "--nit-budget",
            "--run-total-tokens",
            "--degrade-at",
            "--publish-unverified",
            "--exec",
            "--ledger-db",
            "--model-strong",
            "--model-light",
            "--fallback-chain",
            "--rpm",
            "--spot-check-percent",
            "--crosschecker-modes",
            "--crosschecker-history-weeks",
            "--trusted-authors",
            "--pr-author",
            "--tests-status" -> {
          if (i + 1 >= args.length) {
            throw new CraConfigException("参数缺值: " + arg);
          }
          named.put(arg, args[++i]);
        }
        default -> throw new CraConfigException("未知参数: " + arg);
      }
    }
    // dry-run：CI 默认 false；本地模式默认 true（05§3，显式 --dry-run=false 可关）
    boolean dryRun = (local && !dryRunArgSeen) || dryRunFlag;

    String repo = named.get("--repo");
    if (repo == null || repo.isBlank()) {
      repo = env.get("GITHUB_REPOSITORY");
    }
    String[] ownerRepo = null;
    if (repo != null && !repo.isBlank()) {
      ownerRepo = repo.split("/");
      if (ownerRepo.length != 2) {
        throw new CraConfigException("--repo 形如 owner/name: " + repo);
      }
    }
    int pr =
        local
            ? parseIntOrDefault(named.get("--pr"), 0)
            : parsePr(required(named, "--pr", env.get("PR_NUMBER")));
    String base =
        named.containsKey("--base") || !local
            ? required(named, "--base", env.get("BASE_SHA"))
            : "main";
    // 本地模式 head 可省（= HEAD）；CI 必填
    String head = named.get("--head");
    if (head == null || head.isBlank()) {
      head = local ? null : required(named, "--head", env.get("HEAD_SHA"));
    }
    String depth = named.getOrDefault("--depth", "standard");
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（quick|standard|deep）");
    }
    String token = named.get("--token");
    if (token == null || token.isBlank()) {
      token = env.get("GITHUB_TOKEN");
    }
    if (!local) {
      if (token == null || token.isBlank()) {
        throw new CraConfigException("缺参数: --token（或 GITHUB_TOKEN）");
      }
      if (ownerRepo == null) {
        throw new CraConfigException("缺参数: --repo（或 GITHUB_REPOSITORY）");
      }
      if (head == null) {
        throw new CraConfigException("缺参数: --head（或 HEAD_SHA）");
      }
    }
    String llmApiKey = env.get("CRA_LLM_API_KEY");
    String llmBaseUrl = env.get("CRA_LLM_BASE_URL");
    String llmModel = env.get("CRA_LLM_MODEL");
    if (!local) {
      if (llmApiKey == null || llmApiKey.isBlank()) {
        throw new CraConfigException("CRA_LLM_API_KEY 未设置（密钥只从环境变量读，05§6）");
      }
      if (llmBaseUrl == null || llmBaseUrl.isBlank()) {
        throw new CraConfigException("CRA_LLM_BASE_URL 未设置");
      }
      if (llmModel == null || llmModel.isBlank()) {
        throw new CraConfigException("CRA_LLM_MODEL 未设置");
      }
    }
    // 05§5 review.* 与 agents.budget（默认值即文档基准值；REVIEW.md 显式配置在 core 内优先于 nit 默认）
    DegradeAt degradeAt = parseDegradeAt(named.getOrDefault("--degrade-at", "0.70,0.85"));
    String publishUnverified = named.getOrDefault("--publish-unverified", "never");
    if (!"never".equals(publishUnverified) && !"labeled".equals(publishUnverified)) {
      throw new CraConfigException(
          "--publish-unverified 非法: " + publishUnverified + "（never|labeled）");
    }
    // 05§5 agents.crosschecker：默认关闭（关闭不动 Standard 基线算术，04§2.2）
    boolean crossCheckerEnabled =
        crossCheckerSeen
            ? crossChecker
            : Boolean.parseBoolean(env.getOrDefault("CRA_CROSSCHECKER_ENABLED", "false"));
    String crossCheckerModes =
        named.getOrDefault(
            "--crosschecker-modes", env.getOrDefault("CRA_CROSSCHECKER_MODES", "assumption,sniff"));
    validateCrossCheckerModes(crossCheckerModes);
    int crossCheckerHistoryWeeks =
        parseIntOption(
            "--crosschecker-history-weeks",
            named.containsKey("--crosschecker-history-weeks")
                ? named.get("--crosschecker-history-weeks")
                : env.get("CRA_CROSSCHECKER_HISTORY_WEEKS"),
            8);
    // 05§5 agents.signoff（M4a）：默认开启；作者/测试状态未知时低风险 0 人签条件不满足
    boolean signoffEnabled =
        signoffSeen
            ? signoff
            : Boolean.parseBoolean(env.getOrDefault("CRA_SIGNOFF_ENABLED", "true"));
    List<String> trustedAuthors =
        parseCsv(
            named.getOrDefault(
                "--trusted-authors", env.getOrDefault("CRA_SIGNOFF_TRUSTED_AUTHORS", "")));
    String prAuthor = blankToNull(named.get("--pr-author"));
    if (prAuthor == null) {
      prAuthor = blankToNull(env.get("CRA_PR_AUTHOR"));
    }
    if (prAuthor == null) {
      prAuthor = blankToNull(env.get("GITHUB_ACTOR"));
    }
    String testsStatus =
        named.getOrDefault("--tests-status", env.getOrDefault("CRA_TESTS_STATUS", "unknown"));
    try {
      org.cra.common.signoff.TestsStatus.parse(testsStatus);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("--tests-status 非法: " + testsStatus + "（passed|failed|unknown）");
    }
    SignoffConfig signoffConfig =
        new SignoffConfig(
            signoffEnabled, trustedAuthors, prAuthor == null ? "" : prAuthor, testsStatus);
    return new CraConfig(
        ownerRepo == null ? null : ownerRepo[0],
        ownerRepo == null ? null : ownerRepo[1],
        pr,
        base,
        head,
        depth,
        dryRun,
        securityBlock,
        token,
        named.getOrDefault("--github-api", "https://api.github.com"),
        llmApiKey,
        llmBaseUrl,
        llmModel,
        named.getOrDefault("--run-root", ".cra/runs"),
        named.getOrDefault("--workspace", "."),
        parseIntOption(named, "--large-diff-lines", 2000),
        parseIntOption(named, "--hard-cap-lines", 8000),
        parseNitBudget(named),
        parseLongOption(named, "--run-total-tokens", 500_000L),
        degradeAt.first(),
        degradeAt.second(),
        publishUnverified,
        parseIntOption(named, "--spot-check-percent", 20),
        local,
        named.getOrDefault("--exec", DEFAULT_EXEC),
        expandUserHome(named.getOrDefault("--ledger-db", "~/.cra/ledger.db")),
        blankToNull(named.getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG"))),
        blankToNull(named.getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT"))),
        named.getOrDefault(
            "--fallback-chain", env.getOrDefault("CRA_LLM_FALLBACK_CHAIN", "standard,light")),
        named.getOrDefault("--rpm", env.getOrDefault("CRA_LLM_RPM", "")),
        crossCheckerEnabled,
        crossCheckerModes,
        crossCheckerHistoryWeeks,
        blankToNull(named.getOrDefault("--llm-temperature", env.get("CRA_LLM_TEMPERATURE"))),
        signoffConfig);
  }

  /** 逗号分隔列表（空/空白项丢弃；用于 trusted-authors 与模式串）。 */
  private static List<String> parseCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> values = new java.util.ArrayList<>();
    for (String part : raw.split(",")) {
      if (!part.isBlank()) {
        values.add(part.strip());
      }
    }
    return List.copyOf(values);
  }

  /** 空白值归一为 null：GitHub Actions 的空 env 是 ""，语义等同未设置（档位缺省继承 standard）。 */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 模式串校验（assumption|sniff，逗号分隔，至少一个；03§3.1）。 */
  private static void validateCrossCheckerModes(String modes) {
    boolean any = false;
    for (String part : modes.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        org.cra.core.api.CrossCheckerOptions.Mode.parse(part);
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("--crosschecker-modes 非法: " + modes + "（assumption|sniff）");
      }
      any = true;
    }
    if (!any) {
      throw new CraConfigException("--crosschecker-modes 不能为空（assumption|sniff）");
    }
  }

  private static String expandUserHome(String path) {
    if (path.startsWith("~")) {
      return System.getProperty("user.home") + path.substring(1);
    }
    return path;
  }

  private static int parseIntOrDefault(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException("--pr 必须为非负整数（本地模式 0 = 无 PR）: " + value);
    }
  }

  private record DegradeAt(double first, double second) {}

  private static DegradeAt parseDegradeAt(String value) {
    String[] parts = value.split(",");
    if (parts.length != 2) {
      throw new CraConfigException("--degrade-at 形如 0.70,0.85: " + value);
    }
    try {
      double first = Double.parseDouble(parts[0].strip());
      double second = Double.parseDouble(parts[1].strip());
      if (first <= 0 || second <= first || second > 1.0) {
        throw new NumberFormatException();
      }
      return new DegradeAt(first, second);
    } catch (NumberFormatException e) {
      throw new CraConfigException("--degrade-at 阈值非法（0 < first < second <= 1）: " + value);
    }
  }

  private static int parseNitBudget(Map<String, String> named) {
    // 04§3.1：0 = 禁止 nit，是合法配置
    String value = named.get("--nit-budget");
    if (value == null) {
      return 3;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException("--nit-budget 必须为非负整数（0 = 禁止）: " + value);
    }
  }

  private static int parseIntOption(Map<String, String> named, String key, int defaultValue) {
    String value = named.get(key);
    return parseIntOption(key, value, defaultValue);
  }

  private static int parseIntOption(String key, String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为正整数: " + value);
    }
  }

  private static long parseLongOption(Map<String, String> named, String key, long defaultValue) {
    String value = named.get(key);
    if (value == null) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为正整数: " + value);
    }
  }

  private static String required(Map<String, String> named, String key, String envValue) {
    String value = named.get(key);
    if (value == null || value.isBlank()) {
      value = envValue;
    }
    if (value == null || value.isBlank()) {
      throw new CraConfigException("缺参数: " + key + "（或对应环境变量）");
    }
    return value;
  }

  private static int parsePr(String value) {
    try {
      int pr = Integer.parseInt(value);
      if (pr <= 0) {
        throw new NumberFormatException();
      }
      return pr;
    } catch (NumberFormatException e) {
      throw new CraConfigException("--pr 必须为正整数: " + value);
    }
  }
}
