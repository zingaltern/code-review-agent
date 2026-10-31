package org.cra.runner.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.agents.api.AgentBudgetOverrides;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;
import org.cra.common.signoff.TestsStatus;
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
  private static final Set<String> BUDGET_FLAGS = budgetFlags();

  /** 带值参数（flag 后紧跟一个值；与 {@link #TRI_STATE_FLAGS} 的布尔旗标互斥）。 */
  private static final Set<String> VALUE_FLAGS = valueFlags();

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
    Scan scan = scan(stripReviewPrefix(args));
    String platform = PlatformArgs.platform(scan.named(), env);
    boolean gitlab = PlatformArgs.GITLAB.equals(platform);
    Target target = resolveTarget(scan, env, gitlab);
    PlatformArgs.LlmEnvTriple llm = PlatformArgs.llmEnvTriple(env, !scan.local());
    Limits limits = parseLimits(scan.named(), env);
    Advanced advanced = parseAdvanced(scan, env);
    SignoffConfig signoff = parseSignoff(scan, env);
    LlmTuning tuning = parseLlmTuning(scan.named(), env);
    return new CraConfig(
        target.ownerRepo() == null ? null : target.ownerRepo().owner(),
        target.ownerRepo() == null ? null : target.ownerRepo().name(),
        target.prNumber(),
        target.base(),
        target.head(),
        target.depth(),
        dryRun(scan),
        scan.securityBlock(),
        // GitLab 模式下 token 只属于 GitLabSettings：带上 githubToken 会让 push 基线解析把 PAT 发给 GitHub
        gitlab ? null : target.token(),
        scan.named().getOrDefault("--github-api", PlatformArgs.DEFAULT_GITHUB_API_BASE),
        llm.apiKey(),
        llm.baseUrl(),
        llm.model(),
        scan.named().getOrDefault("--run-root", CraConfig.DEFAULT_RUN_ROOT),
        scan.named().getOrDefault("--workspace", "."),
        limits.largeDiffLines(),
        limits.hardCapLines(),
        limits.nitBudget(),
        limits.runTotalTokens(),
        limits.degradeFirst(),
        limits.degradeSecond(),
        limits.publishUnverified(),
        limits.spotCheckPercent(),
        scan.local(),
        scan.named().getOrDefault("--exec", DEFAULT_EXEC),
        expandUserHome(scan.named().getOrDefault("--ledger-db", CraConfig.DEFAULT_LEDGER_DB)),
        tuning.strongModel(),
        tuning.lightModel(),
        tuning.fallbackChain(),
        tuning.rpm(),
        tuning.maxConcurrency(),
        advanced.crossCheckerEnabled(),
        advanced.crossCheckerModes(),
        advanced.historyWeeks(),
        tuning.temperature(),
        advanced.pushLookback(),
        scan.push() ? ReviewMode.COMMIT : ReviewMode.PULL_REQUEST,
        signoff,
        platform,
        // 本地形态与平台无关（05§3）：不读取 GitLab token/文件，避免残留 CRA_GITLAB_TOKEN_FILE 拖垮本地启动
        gitlab && !scan.local()
            ? PlatformArgs.gitlabSettings(scan.named(), env)
            : GitLabSettings.none(),
        advanced.semanticContext(),
        advanced.includeTestFiles(),
        agentBudgets(scan.named(), env));
  }

  /** 参数扫描结果（named 值参数 + tri-state 取值 + 三个布尔开关）。 */
  private record Scan(
      Map<String, String> named,
      Map<String, Boolean> triValues,
      Set<String> triSeen,
      boolean securityBlock,
      boolean local,
      boolean push) {}

  /** 仓库/PR/区间/token 解析结果（本地与 CI 形态的差异收敛于此）。 */
  private record Target(
      PlatformArgs.OwnerRepo ownerRepo,
      int prNumber,
      String base,
      String head,
      String depth,
      String token) {}

  /** 预算/阈值配置（05§5 review.* 与 agents.budget.*）。 */
  private record Limits(
      int largeDiffLines,
      int hardCapLines,
      int nitBudget,
      long runTotalTokens,
      double degradeFirst,
      double degradeSecond,
      String publishUnverified,
      int spotCheckPercent) {}

  /** CrossChecker / 语义上下文 / 测试范围 / push 回溯配置。 */
  private record Advanced(
      boolean crossCheckerEnabled,
      String crossCheckerModes,
      int historyWeeks,
      SemanticContextConfig semanticContext,
      int pushLookback,
      boolean includeTestFiles) {}

  /** LLM 档位与采样配置（05§5 models.* / fallback-chain / per-model-rpm / max-concurrency）。 */
  private record LlmTuning(
      String strongModel,
      String lightModel,
      String fallbackChain,
      String rpm,
      int maxConcurrency,
      String temperature) {}

  /** 可选 review 子命令前缀（05§3/05§4：java -jar cra-runner-cli.jar review …）。 */
  private static String[] stripReviewPrefix(String[] args) {
    return args.length > 0 && "review".equals(args[0])
        ? Arrays.copyOfRange(args, 1, args.length)
        : args;
  }

  private static Scan scan(String[] args) {
    Map<String, String> named = new HashMap<>();
    Map<String, Boolean> triValues = new HashMap<>();
    Set<String> triSeen = new HashSet<>();
    boolean securityBlock = false;
    boolean local = false;
    boolean push = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (VALUE_FLAGS.contains(arg)) {
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
        default -> throw unknownArg(arg);
      }
    }
    return new Scan(named, triValues, triSeen, securityBlock, local, push);
  }

  /** 未知参数回显只看旗标名：`--token=ghp_...` 这类写法不得把密钥写进结构化日志（05§6）。 */
  private static CraConfigException unknownArg(String arg) {
    int eq = arg.indexOf('=');
    String flag = eq > 0 ? arg.substring(0, eq) : arg;
    return new CraConfigException("未知参数: " + flag);
  }

  /** dry-run：CI 默认 false；本地模式默认 true（05§3，显式 --dry-run=false 可关）——无 env 键的 tri-state。 */
  private static boolean dryRun(Scan scan) {
    return (scan.local() && !scan.triSeen().contains(DRY_RUN.flag()))
        || scan.triValues().getOrDefault(DRY_RUN.flag(), false);
  }

  /** 仓库标识、PR/区间、深度与 token 的解析（平台选择已由调用方完成）。 */
  private static Target resolveTarget(Scan scan, Map<String, String> env, boolean gitlab) {
    Map<String, String> named = scan.named();
    boolean local = scan.local();
    String repo = PlatformArgs.repoOrProject(named, env, gitlab);
    PlatformArgs.OwnerRepo ownerRepo = repo == null ? null : PlatformArgs.splitRepo(repo, gitlab);
    int pr;
    String base;
    if (scan.push()) {
      if (local) {
        throw new CraConfigException("--push 与 --local 互斥（push 形态走平台 commit comment，05§2.4）");
      }
      if (named.containsKey("--pr")) {
        throw new CraConfigException("--push 模式无 PR 编号（账本键 = repo + commit，05§1.3）");
      }
      pr = 0;
      // push 基线：--base / --baseline 显式给出即用；缺省由命令执行期沿首父链查账本解析
      // （上一完成 commit；首次 = 仓库初版，05§2.4）
      String explicitBase = PlatformArgs.blankToNull(named.get("--base"));
      base =
          explicitBase != null ? explicitBase : PlatformArgs.blankToNull(named.get("--baseline"));
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
    String head = PlatformArgs.blankToNull(named.get("--head"));
    if (head == null) {
      head =
          local
              ? null
              : required(named, "--head", gitlab ? env.get("CI_COMMIT_SHA") : env.get("HEAD_SHA"));
    }
    String depth = PlatformArgs.depthCheck(named.getOrDefault("--depth", "standard"));
    // token 解析（R5 共享）：GitLab 拒收 --token；本地形态与平台无关，不读取任何远端 token
    String token = local ? null : PlatformArgs.tokenOrGitlabGuard(named, env, gitlab);
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
    return new Target(ownerRepo, pr, base, head, depth, token);
  }

  /** 预算/阈值解析（05§5 review.*；flag &gt; CRA_REVIEW_* env &gt; 基准值，解析器与 sweep 共享于 PlatformArgs）。 */
  private static Limits parseLimits(Map<String, String> named, Map<String, String> env) {
    PipelineOptions defaults = PipelineOptions.defaults();
    PlatformArgs.Sourced degradeAtValue =
        PlatformArgs.policyValue(named, env, "--degrade-at", "CRA_REVIEW_DEGRADE_AT");
    PlatformArgs.DegradeAt degradeAt =
        PlatformArgs.degradeAt(
            degradeAtValue.key(),
            degradeAtValue.value(),
            new PlatformArgs.DegradeAt(defaults.degradeAtFirst(), defaults.degradeAtSecond()));
    PlatformArgs.Sourced largeValue =
        PlatformArgs.policyValue(named, env, "--large-diff-lines", "CRA_REVIEW_LARGE_DIFF_LINES");
    int largeDiffLines =
        PlatformArgs.positiveInt(largeValue.key(), largeValue.value(), defaults.largeDiffLines());
    PlatformArgs.Sourced hardValue =
        PlatformArgs.policyValue(named, env, "--hard-cap-lines", "CRA_REVIEW_HARD_CAP_LINES");
    int hardCapLines =
        PlatformArgs.positiveInt(hardValue.key(), hardValue.value(), defaults.hardCapLines());
    if (hardCapLines <= largeDiffLines) {
      throw new CraConfigException(
          hardValue.key()
              + " 必须大于 "
              + largeValue.key()
              + ": "
              + hardCapLines
              + " <= "
              + largeDiffLines);
    }
    PlatformArgs.Sourced spotValue =
        PlatformArgs.policyValue(
            named, env, "--spot-check-percent", "CRA_REVIEW_SPOT_CHECK_PERCENT");
    int spotCheckPercent =
        PlatformArgs.percent(
            spotValue.key(), spotValue.value(), defaults.importantRefutedSpotCheckPercent());
    PlatformArgs.Sourced publishValue =
        PlatformArgs.policyValue(
            named, env, "--publish-unverified", "CRA_REVIEW_PUBLISH_UNVERIFIED");
    String publishUnverified =
        PlatformArgs.publishUnverified(
            publishValue.key(), publishValue.value(), defaults.publishUnverified());
    PlatformArgs.Sourced nitValue =
        PlatformArgs.policyValue(named, env, "--nit-budget", "CRA_REVIEW_NIT_BUDGET");
    return new Limits(
        largeDiffLines,
        hardCapLines,
        PlatformArgs.nitBudget(nitValue.key(), nitValue.value(), defaults.nitBudgetDefault()),
        parseLongOption(named, "--run-total-tokens", PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS),
        degradeAt.first(),
        degradeAt.second(),
        publishUnverified,
        spotCheckPercent);
  }

  /** CrossChecker / 语义上下文 / 测试范围 / push 回溯解析。 */
  private static Advanced parseAdvanced(Scan scan, Map<String, String> env) {
    Map<String, String> named = scan.named();
    boolean crossCheckerEnabled =
        resolveTriState(CROSSCHECKER, scan.triSeen(), scan.triValues(), env);
    boolean includeTestFiles = resolveTriState(REVIEW_TESTS, scan.triSeen(), scan.triValues(), env);
    String crossCheckerModes = PlatformArgs.crosscheckerModes(named, env);
    int crossCheckerHistoryWeeks =
        parseIntOption(
            "--crosschecker-history-weeks",
            optionOrEnv(
                named, env, "--crosschecker-history-weeks", "CRA_CROSSCHECKER_HISTORY_WEEKS"),
            8);
    // 05§5 semantic_context（M4e，ADR-0014）：默认关闭；开启后仅 standard/deep 档生效
    SemanticContextConfig semanticContextConfig =
        semanticContextConfig(
            named, env, resolveTriState(SEMANTIC_CONTEXT, scan.triSeen(), scan.triValues(), env));
    int pushLookback =
        parseIntOption(
            "--lookback",
            optionOrEnv(named, env, "--lookback", "CRA_PUSH_LOOKBACK"),
            CraConfig.DEFAULT_PUSH_LOOKBACK);
    return new Advanced(
        crossCheckerEnabled,
        crossCheckerModes,
        crossCheckerHistoryWeeks,
        semanticContextConfig,
        pushLookback,
        includeTestFiles);
  }

  /** 签字建议配置（05§5 agents.signoff；M4a）。 */
  private static SignoffConfig parseSignoff(Scan scan, Map<String, String> env) {
    Map<String, String> named = scan.named();
    boolean signoffEnabled = resolveTriState(SIGNOFF, scan.triSeen(), scan.triValues(), env);
    List<String> trustedAuthors =
        parseCsv(
            named.getOrDefault(
                "--trusted-authors", PlatformArgs.envOr(env, "CRA_SIGNOFF_TRUSTED_AUTHORS", "")));
    String prAuthor = PlatformArgs.blankToNull(named.get("--pr-author"));
    if (prAuthor == null) {
      prAuthor = PlatformArgs.blankToNull(env.get("CRA_PR_AUTHOR"));
    }
    if (prAuthor == null) {
      prAuthor = PlatformArgs.blankToNull(env.get("GITHUB_ACTOR"));
    }
    String testsStatus =
        named.getOrDefault(
            "--tests-status", PlatformArgs.envOr(env, "CRA_TESTS_STATUS", "unknown"));
    try {
      TestsStatus.parse(testsStatus);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("--tests-status 非法: " + testsStatus + "（passed|failed|unknown）");
    }
    return new SignoffConfig(
        signoffEnabled, trustedAuthors, prAuthor == null ? "" : prAuthor, testsStatus);
  }

  /** LLM 档位/降级链/RPM/温度/并发解析（05§5；与 cra-server 组合根同语义，公共体在 LlmParsing）。 */
  private static LlmTuning parseLlmTuning(Map<String, String> named, Map<String, String> env) {
    String strongModel =
        PlatformArgs.blankToNull(
            named.getOrDefault("--model-strong", env.get("CRA_LLM_MODEL_STRONG")));
    String lightModel =
        PlatformArgs.blankToNull(
            named.getOrDefault("--model-light", env.get("CRA_LLM_MODEL_LIGHT")));
    String fallbackChain =
        named.getOrDefault(
            "--fallback-chain",
            PlatformArgs.envOr(env, "CRA_LLM_FALLBACK_CHAIN", CraConfig.DEFAULT_FALLBACK_CHAIN));
    String rpm = named.getOrDefault("--rpm", PlatformArgs.envOr(env, "CRA_LLM_RPM", ""));
    // CRA_LLM_MAX_CONCURRENCY 只走环境变量（CI 配置面；不为单一上限新增 flag）
    int maxConcurrency =
        LlmParsing.parseMaxConcurrency(
            PlatformArgs.blankToNull(env.get("CRA_LLM_MAX_CONCURRENCY")),
            "CRA_LLM_MAX_CONCURRENCY");
    String temperature =
        PlatformArgs.blankToNull(
            named.getOrDefault("--llm-temperature", env.get("CRA_LLM_TEMPERATURE")));
    return new LlmTuning(strongModel, lightModel, fallbackChain, rpm, maxConcurrency, temperature);
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
    return Boolean.parseBoolean(
        PlatformArgs.envOr(env, flag.envKey(), Boolean.toString(flag.defaultValue())));
  }

  /**
   * 语义上下文配置（M4e，ADR-0014）：开关由 tri-state 描述符解析后传入；上限默认 maxFiles=2000 / maxFileBytes=262144 /
   * sliceTokens=6000；越界由 {@link SemanticContextConfig} 构造期拒绝（CraConfigException → 退出码 2，05§3）。
   */
  private static SemanticContextConfig semanticContextConfig(
      Map<String, String> named, Map<String, String> env, boolean enabled) {
    SemanticContextConfig defaults = SemanticContextConfig.defaults();
    int maxFiles =
        parseIntOption(
            "--semantic-context-max-files",
            optionValue(
                named, env, "--semantic-context-max-files", "CRA_SEMANTIC_CONTEXT_MAX_FILES"),
            defaults.maxFiles());
    long maxFileBytes =
        parseLongOption(
            "--semantic-context-max-file-bytes",
            optionValue(
                named,
                env,
                "--semantic-context-max-file-bytes",
                "CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES"),
            defaults.maxFileBytes());
    long sliceTokens =
        parseLongOption(
            "--semantic-context-slice-tokens",
            optionValue(
                named, env, "--semantic-context-slice-tokens", "CRA_SEMANTIC_CONTEXT_SLICE_TOKENS"),
            defaults.sliceTokens());
    return new SemanticContextConfig(enabled, maxFiles, maxFileBytes, sliceTokens);
  }

  /** 逗号分隔列表（空/空白项丢弃；用于 trusted-authors）。 */
  private static List<String> parseCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : raw.split(",")) {
      if (!part.isBlank()) {
        values.add(part.strip());
      }
    }
    return List.copyOf(values);
  }

  /** flag 优先、环境变量次之；两者都缺省返回 null，由解析器取默认值。 */
  private static String optionValue(
      Map<String, String> named, Map<String, String> env, String flag, String envKey) {
    String value = named.get(flag);
    return value != null ? value : PlatformArgs.blankToNull(env.get(envKey));
  }

  /** 显式 flag（含空白值）优先，否则取空白归一后的 env；两者皆缺省为 null。 */
  private static String optionOrEnv(
      Map<String, String> named, Map<String, String> env, String flag, String envKey) {
    return named.containsKey(flag) ? named.get(flag) : PlatformArgs.blankToNull(env.get(envKey));
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
      int parsed = Integer.parseInt(value.strip());
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException("--pr 必须为非负整数（本地模式 0 = 无 PR）: " + value);
    }
  }

  private static int parseIntOption(String key, String value, int defaultValue) {
    return PlatformArgs.positiveInt(key, value, defaultValue);
  }

  private static long parseLongOption(Map<String, String> named, String key, long defaultValue) {
    return parseLongOption(key, named.get(key), defaultValue);
  }

  private static long parseLongOption(String key, String value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value.strip());
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
      int pr = Integer.parseInt(value.strip());
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
            (flag, envKey) ->
                PlatformArgs.blankToNull(
                    PlatformArgs.firstNonBlank(named.get(flag), env.get(envKey))));
    try {
      return AgentBudgetOverrides.fromConfig(values);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("agents.budget 非法: " + e.getMessage());
    }
  }

  private static Set<String> budgetFlags() {
    Set<String> flags = new HashSet<>();
    for (String role : AgentBudgetOverrides.CONFIGURABLE_ROLES) {
      flags.add("--" + role + "-input-tokens");
      flags.add("--" + role + "-output-tokens");
    }
    return Set.copyOf(flags);
  }

  private static Set<String> valueFlags() {
    Set<String> flags =
        new HashSet<>(
            List.of(
                "--repo",
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
                "--tests-status"));
    flags.addAll(BUDGET_FLAGS);
    return Set.copyOf(flags);
  }
}
