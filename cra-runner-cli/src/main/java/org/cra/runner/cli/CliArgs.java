package org.cra.runner.cli;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.agents.api.AgentBudgetOverrides;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.PipelineOptions;
import org.cra.llm.api.LlmParsing;

/**
 * `cra review` 参数解析（05§3 CLI 契约）。缺参/非法值 → CraConfigException（退出码 2）。 参数优先于环境变量；密钥类只认环境变量来源的传递（CI 中经
 * --token 传入亦视为环境侧提供）。
 *
 * <p>本地 pre-PR 模式（--local，T2.10）：--repo/--pr/--head/--token 与 CRA_LLM_* 全部转为可选—— 仓库身份由 remote
 * origin 推导、head 解析为 HEAD、无密钥时评审段如实跳过；dry-run 默认 true（05§3）。 tri-state 旗标（R5）由 {@link
 * #TRI_STATE_FLAGS} 描述符表收敛；仓库/token/LLM/depth/modes 的共享解析在 {@link PlatformArgs}。
 */
final class CliArgs {

  /** 本地实跑命令默认值（07 T2.10：默认 mvn -B -ntp verify，docs/00 §4 自检即现成命令）。 */
  static final String DEFAULT_EXEC = "mvn -B -ntp verify";

  /** 单代理预算覆盖 flag（05§5 agents.budget.&lt;role&gt;.*；由角色表生成，避免 16 处手写）。 */
  private static final java.util.Set<String> BUDGET_FLAGS = budgetFlags();

  /**
   * tri-state 旗标描述符（R5：15 个 case 标签收敛为 5 行表）。三种形式：{@code --flag} / {@code --flag=true} / {@code
   * --flag=false}；取值优先级 = 显式 flag &gt; env &gt; 默认。{@code --flag=value} 形式仅本表旗标合法——其余参数按未知处理（`=
   * 截断掩码回显旗标名）。
   *
   * @param flag 旗标名
   * @param envKey 环境变量回退键（null = 无 env 键——dry-run 的默认值随 --local 形态变化，不走 env）
   * @param defaultValue env 也未配置时的默认值（envKey=null 时无意义）
   */
  private record TriStateFlag(String flag, String envKey, boolean defaultValue) {}

  /** dry-run：无 env 键（语义差异保留：CI 默认 false、本地默认 true，05§3）。 */
  private static final TriStateFlag DRY_RUN = new TriStateFlag("--dry-run", null, false);

  /** CrossChecker 总开关（05§5 agents.crosschecker：默认关闭，关闭不动基线算术 04§2.2）。 */
  private static final TriStateFlag CROSSCHECKER =
      new TriStateFlag("--crosschecker", "CRA_CROSSCHECKER_ENABLED", false);

  /** agents.signoff（M4a：默认开启）。 */
  private static final TriStateFlag SIGNOFF =
      new TriStateFlag("--signoff", "CRA_SIGNOFF_ENABLED", true);

  /** 语义上下文（M4e，ADR-0014：默认关闭）。 */
  private static final TriStateFlag SEMANTIC_CONTEXT =
      new TriStateFlag("--semantic-context", "CRA_SEMANTIC_CONTEXT_ENABLED", false);

  /** 测试文件评审范围（02§4.4：默认不评审）。 */
  private static final TriStateFlag REVIEW_TESTS =
      new TriStateFlag("--review-tests", "CRA_REVIEW_TESTS", false);

  private static final List<TriStateFlag> TRI_STATE_FLAGS =
      List.of(DRY_RUN, CROSSCHECKER, SIGNOFF, SEMANTIC_CONTEXT, REVIEW_TESTS);

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
    Map<String, Boolean> triValues = new HashMap<>();
    Set<String> triSeen = new HashSet<>();
    boolean securityBlock = false;
    boolean local = false;
    boolean push = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (BUDGET_FLAGS.contains(arg)) {
        if (i + 1 >= args.length) {
          throw new CraConfigException("参数缺值: " + arg);
        }
        named.put(arg, args[++i]);
        continue;
      }
      TriStateMatch triState = matchTriState(arg);
      if (triState != null) {
        triSeen.add(triState.flag().flag());
        triValues.put(triState.flag().flag(), triState.value());
        continue;
      }
      switch (arg) {
        case "--security-block" -> securityBlock = true;
        case "--local" -> local = true;
        case "--push" -> push = true;
        case "--repo",
            "--pr",
            "--base",
            "--baseline",
            "--lookback",
            "--head",
            "--depth",
            "--token",
            "--platform",
            "--github-api",
            "--gitlab-base-url",
            "--gitlab-project",
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
            "--llm-temperature",
            "--fallback-chain",
            "--rpm",
            "--spot-check-percent",
            "--crosschecker-modes",
            "--crosschecker-history-weeks",
            "--semantic-context-max-files",
            "--semantic-context-max-file-bytes",
            "--semantic-context-slice-tokens",
            "--trusted-authors",
            "--pr-author",
            "--tests-status" -> {
          if (i + 1 >= args.length) {
            throw new CraConfigException("参数缺值: " + arg);
          }
          named.put(arg, args[++i]);
        }
        default -> {
          // 未知参数回显只看旗标名：`--token=ghp_...` 这类写法不得把密钥写进结构化日志（05§6）
          int eq = arg.indexOf('=');
          String flag = eq > 0 ? arg.substring(0, eq) : arg;
          throw new CraConfigException("未知参数: " + flag);
        }
      }
    }
    // dry-run：CI 默认 false；本地模式默认 true（05§3，显式 --dry-run=false 可关）——无 env 键的 tri-state
    boolean dryRun =
        (local && !triSeen.contains(DRY_RUN.flag()))
            || triValues.getOrDefault(DRY_RUN.flag(), false);

    // 平台选择（M4e 第二半，ADR-0013）：默认 github 保持兼容；本地 pre-PR 形态与平台无关
    String platform = PlatformArgs.platform(named, env);
    boolean gitlab = PlatformArgs.GITLAB.equals(platform);

    // 仓库标识回退（R5 共享）：本地形态可缺省（remote origin 推导），CI 必填在下方 !local 块拒绝
    String repo = PlatformArgs.repoOrProject(named, env, gitlab);
    String[] ownerRepo =
        repo == null || repo.isBlank() ? null : PlatformArgs.splitRepo(repo, gitlab);
    int pr;
    String base;
    if (push) {
      if (local) {
        throw new CraConfigException("--push 与 --local 互斥（push 形态走平台 commit comment，05§2.4）");
      }
      if (named.containsKey("--pr")) {
        throw new CraConfigException("--push 模式无 PR 编号（账本键 = repo + commit，05§1.3）");
      }
      pr = 0;
      // push 基线：--base / --baseline 显式给出即用；缺省由命令执行期沿首父链查账本解析
      // （上一完成 commit；首次 = 仓库初版，05§2.4）
      String explicitBase = blankToNull(named.get("--base"));
      base = explicitBase != null ? explicitBase : blankToNull(named.get("--baseline"));
    } else {
      String prEnv = gitlab ? env.get("CI_MERGE_REQUEST_IID") : env.get("PR_NUMBER");
      pr =
          local ? parseIntOrDefault(named.get("--pr"), 0) : parsePr(required(named, "--pr", prEnv));
      base =
          named.containsKey("--base") || !local
              ? required(
                  named,
                  "--base",
                  gitlab ? env.get("CI_MERGE_REQUEST_DIFF_BASE_SHA") : env.get("BASE_SHA"))
              : "main";
    }
    // 本地模式 head 可省（= HEAD）；CI 必填
    String head = named.get("--head");
    if (head == null || head.isBlank()) {
      head =
          local
              ? null
              : required(named, "--head", gitlab ? env.get("CI_COMMIT_SHA") : env.get("HEAD_SHA"));
    }
    String depth = PlatformArgs.depthCheck(named.getOrDefault("--depth", "standard"));
    // token 解析（R5 共享）：GitLab 拒收 --token；必填性按形态校验（本地可缺省）
    String token = PlatformArgs.tokenOrGitlabGuard(named, env, gitlab);
    if (!local) {
      if (gitlab && (token == null || token.isBlank())) {
        throw new CraConfigException("CRA_GITLAB_TOKEN 未设置（或 CRA_GITLAB_TOKEN_FILE 指向密钥文件；05§6）");
      }
      if (!gitlab && (token == null || token.isBlank())) {
        throw new CraConfigException("缺参数: --token（或 GITHUB_TOKEN）");
      }
      if (ownerRepo == null) {
        throw new CraConfigException(PlatformArgs.missingRepoMessage(gitlab));
      }
      if (head == null) {
        throw new CraConfigException(
            gitlab ? "缺参数: --head（或 CI_COMMIT_SHA）" : "缺参数: --head（或 HEAD_SHA）");
      }
    }
    // LLM env 三键（R5 共享）：本地形态可缺省（无密钥时评审段如实跳过）
    PlatformArgs.LlmEnvTriple llm = PlatformArgs.llmEnvTriple(env, !local);
    // 05§5 review.* 与 agents.budget（默认值即文档基准值；REVIEW.md 显式配置在 core 内优先于 nit 默认）
    DegradeAt degradeAt = parseDegradeAt(named.getOrDefault("--degrade-at", "0.70,0.85"));
    int largeDiffLines =
        parseIntOption(named, "--large-diff-lines", PipelineOptions.DEFAULT_LARGE_DIFF_LINES);
    int hardCapLines =
        parseIntOption(named, "--hard-cap-lines", PipelineOptions.DEFAULT_HARD_CAP_LINES);
    if (hardCapLines <= largeDiffLines) {
      throw new CraConfigException(
          "--hard-cap-lines 必须大于 --large-diff-lines: " + hardCapLines + " <= " + largeDiffLines);
    }
    int spotCheckPercent = parseIntOption(named, "--spot-check-percent", 20);
    if (spotCheckPercent > 100) {
      throw new CraConfigException("--spot-check-percent 必须在 [0,100]: " + spotCheckPercent);
    }
    String publishUnverified = named.getOrDefault("--publish-unverified", "never");
    if (!"never".equals(publishUnverified) && !"labeled".equals(publishUnverified)) {
      throw new CraConfigException(
          "--publish-unverified 非法: " + publishUnverified + "（never|labeled）");
    }
    // 05§5 agents.crosschecker 与 02§4.4 评审范围（R5 描述符表取值：flag > env > 默认）
    boolean crossCheckerEnabled = resolveTriState(CROSSCHECKER, triSeen, triValues, env);
    boolean includeTestFiles = resolveTriState(REVIEW_TESTS, triSeen, triValues, env);
    String crossCheckerModes = PlatformArgs.crosscheckerModes(named, env);
    int crossCheckerHistoryWeeks =
        parseIntOption(
            "--crosschecker-history-weeks",
            named.containsKey("--crosschecker-history-weeks")
                ? named.get("--crosschecker-history-weeks")
                : blankToNull(env.get("CRA_CROSSCHECKER_HISTORY_WEEKS")),
            8);
    // 05§5 semantic_context（M4e，ADR-0014）：默认关闭；开启后仅 standard/deep 档生效
    SemanticContextConfig semanticContextConfig =
        semanticContextConfig(
            named, env, resolveTriState(SEMANTIC_CONTEXT, triSeen, triValues, env));
    int pushLookback =
        parseIntOption(
            "--lookback",
            named.containsKey("--lookback")
                ? named.get("--lookback")
                : blankToNull(env.get("CRA_PUSH_LOOKBACK")),
            CraConfig.DEFAULT_PUSH_LOOKBACK);
    // 05§5 agents.signoff（M4a）：默认开启；作者/测试状态未知时低风险 0 人签条件不满足
    boolean signoffEnabled = resolveTriState(SIGNOFF, triSeen, triValues, env);
    List<String> trustedAuthors =
        parseCsv(
            named.getOrDefault("--trusted-authors", envOr(env, "CRA_SIGNOFF_TRUSTED_AUTHORS", "")));
    String prAuthor = blankToNull(named.get("--pr-author"));
    if (prAuthor == null) {
      prAuthor = blankToNull(env.get("CRA_PR_AUTHOR"));
    }
    if (prAuthor == null) {
      prAuthor = blankToNull(env.get("GITHUB_ACTOR"));
    }
    String testsStatus =
        named.getOrDefault("--tests-status", envOr(env, "CRA_TESTS_STATUS", "unknown"));
    try {
      org.cra.common.signoff.TestsStatus.parse(testsStatus);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("--tests-status 非法: " + testsStatus + "（passed|failed|unknown）");
    }
    SignoffConfig signoffConfig =
        new SignoffConfig(
            signoffEnabled, trustedAuthors, prAuthor == null ? "" : prAuthor, testsStatus);
    AgentBudgetOverrides agentBudgets = agentBudgets(named, env);
    return new CraConfig(
        ownerRepo == null ? null : ownerRepo[0],
        ownerRepo == null ? null : ownerRepo[1],
        pr,
        base,
        head,
        depth,
        dryRun,
        securityBlock,
        // GitLab 模式下 token 只属于 GitLabSettings：带上 githubToken 会让 push 基线解析把 PAT 发给 GitHub
        gitlab ? null : token,
        named.getOrDefault("--github-api", "https://api.github.com"),
        llm.apiKey(),
        llm.baseUrl(),
        llm.model(),
        named.getOrDefault("--run-root", ".cra/runs"),
        named.getOrDefault("--workspace", "."),
        largeDiffLines,
        hardCapLines,
        parseNitBudget(named),
        parseLongOption(named, "--run-total-tokens", PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS),
        degradeAt.first(),
        degradeAt.second(),
        publishUnverified,
        spotCheckPercent,
        local,
        named.getOrDefault("--exec", DEFAULT_EXEC),
        expandUserHome(named.getOrDefault("--ledger-db", "~/.cra/ledger.db")),
        blankToNull(named.getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG"))),
        blankToNull(named.getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT"))),
        named.getOrDefault(
            "--fallback-chain", envOr(env, "CRA_LLM_FALLBACK_CHAIN", "standard,light")),
        named.getOrDefault("--rpm", envOr(env, "CRA_LLM_RPM", "")),
        crossCheckerEnabled,
        crossCheckerModes,
        crossCheckerHistoryWeeks,
        blankToNull(named.getOrDefault("--llm-temperature", env.get("CRA_LLM_TEMPERATURE"))),
        pushLookback,
        push ? org.cra.common.run.ReviewMode.COMMIT : org.cra.common.run.ReviewMode.PULL_REQUEST,
        signoffConfig,
        platform,
        gitlab ? PlatformArgs.gitlabSettings(named, env) : GitLabSettings.none(),
        semanticContextConfig,
        includeTestFiles,
        agentBudgets);
  }

  /** tri-state 旗标匹配（R5 描述符表；`--flag=value` 仅表内旗标合法）。 */
  private static TriStateMatch matchTriState(String arg) {
    for (TriStateFlag flag : TRI_STATE_FLAGS) {
      PlatformArgs.TriState matched = PlatformArgs.matchTriState(arg, flag.flag());
      if (matched != null) {
        return new TriStateMatch(flag, matched.value());
      }
    }
    return null;
  }

  /** tri-state 匹配结果（描述符 + 命中的显式值）。 */
  private record TriStateMatch(TriStateFlag flag, boolean value) {}

  /** tri-state 取值：显式 flag &gt; env &gt; 描述符默认（env 键为 null 的旗标不走 env）。 */
  private static boolean resolveTriState(
      TriStateFlag flag, Set<String> seen, Map<String, Boolean> values, Map<String, String> env) {
    if (seen.contains(flag.flag())) {
      return values.get(flag.flag());
    }
    return Boolean.parseBoolean(envOr(env, flag.envKey(), Boolean.toString(flag.defaultValue())));
  }

  /**
   * 语义上下文配置（M4e，ADR-0014）：开关由 tri-state 描述符解析后传入；上限默认 maxFiles=2000 / maxFileBytes=262144 /
   * sliceTokens=6000；越界由 {@link SemanticContextConfig} 构造期拒绝（CraConfigException → 退出码 2，05§3）。
   */
  private static SemanticContextConfig semanticContextConfig(
      Map<String, String> named, Map<String, String> env, boolean enabled) {
    SemanticContextConfig defaults = SemanticContextConfig.defaults();
    String maxFilesValue =
        optionValue(named, env, "--semantic-context-max-files", "CRA_SEMANTIC_CONTEXT_MAX_FILES");
    String maxFileBytesValue =
        optionValue(
            named, env, "--semantic-context-max-file-bytes", "CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES");
    String sliceTokensValue =
        optionValue(
            named, env, "--semantic-context-slice-tokens", "CRA_SEMANTIC_CONTEXT_SLICE_TOKENS");
    int maxFiles =
        parseIntOption("--semantic-context-max-files", maxFilesValue, defaults.maxFiles());
    long maxFileBytes =
        parseLongOption(
            "--semantic-context-max-file-bytes", maxFileBytesValue, defaults.maxFileBytes());
    long sliceTokens =
        parseLongOption(
            "--semantic-context-slice-tokens", sliceTokensValue, defaults.sliceTokens());
    return new SemanticContextConfig(enabled, maxFiles, maxFileBytes, sliceTokens);
  }

  /** 逗号分隔列表（空/空白项丢弃；用于 trusted-authors）。 */
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

  /** 环境变量读取：空白值等同未设置（GitHub Actions 的空 input 是 ""，不是 null）。 */
  private static String envOr(Map<String, String> env, String key, String defaultValue) {
    String value = blankToNull(env.get(key));
    return value == null ? defaultValue : value;
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
      if (!Double.isFinite(first)
          || !Double.isFinite(second)
          || first <= 0
          || second <= first
          || second > 1.0) {
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

  /** flag 优先、环境变量次之（两者都缺省返回 null，由解析器取默认值）。 */
  private static String optionValue(
      Map<String, String> named, Map<String, String> env, String flag, String envKey) {
    String value = named.get(flag);
    return value != null ? value : env.get(envKey);
  }

  private static long parseLongOption(String key, String value, long defaultValue) {
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

  /**
   * 单代理预算覆盖（05§5 {@code agents.budget.<role>.*}）：flag &gt; 环境变量 &gt; 定义默认（03§6）。
   *
   * <p>flag 形如 {@code --verifier-input-tokens} / {@code --judge-output-tokens}；环境变量形如 {@code
   * CRA_AGENTS_BUDGET_VERIFIER_INPUT_TOKENS}。键集由 {@link AgentBudgetOverrides#CONFIGURABLE_ROLES}
   * 生成—— 未知角色的 flag 仍是"未知参数"（不静默接受拼写错误）。键构建公共体见 {@link LlmParsing#agentBudgetValues}（R4：与 server
   * 同语义收敛）。
   */
  static AgentBudgetOverrides agentBudgets(Map<String, String> named, Map<String, String> env) {
    Map<String, String> values =
        LlmParsing.agentBudgetValues(
            AgentBudgetOverrides.CONFIGURABLE_ROLES,
            (flag, envKey) -> blankToNull(firstNonBlank(named.get(flag), env.get(envKey))));
    try {
      return AgentBudgetOverrides.fromConfig(values);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("agents.budget 非法: " + e.getMessage());
    }
  }

  private static java.util.Set<String> budgetFlags() {
    java.util.Set<String> flags = new java.util.HashSet<>();
    for (String role : AgentBudgetOverrides.CONFIGURABLE_ROLES) {
      flags.add("--" + role + "-input-tokens");
      flags.add("--" + role + "-output-tokens");
    }
    return java.util.Set.copyOf(flags);
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }
}
