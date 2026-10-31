package org.cra.server.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.PipelineOptions;
import org.cra.core.api.SemanticContextOptions;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmParsing;
import org.cra.llm.api.ModelTier;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

/**
 * cra-server 运行配置（05§5 部署级配置的服务形态子集；密钥只从环境变量读，05§6）。
 *
 * <p>组装期硬校验：webhook 密钥 / API token / 签名密钥缺失即拒绝启动（fail closed）。评审参数沿用 05§5 默认值，仅暴露 run 级预算与深度档位；LLM
 * 三档路由与 CrossChecker 开关与 CLI 同语义。
 *
 * @param webhookSecret webhook 密钥（GitHub：X-Hub-Signature-256 HMAC；GitLab：X-Gitlab-Token secret
 *     token）
 * @param platform Git 平台（`github`|`gitlab`；CRA_SERVER_PLATFORM，默认 github；M4e 第二半，ADR-0013）
 * @param gitlabBaseUrl GitLab API 根（含 /api/v4；CRA_GITLAB_BASE_URL）
 * @param gitlabToken GitLab access token（CRA_GITLAB_TOKEN / CRA_GITLAB_TOKEN_FILE；gitlab 模式必填）
 * @param gitlabBotUsername `/user` 不可读时的 bot 身份回退（CRA_GITLAB_BOT_USERNAME，ADR-0011 决策 3）
 * @param apiToken 服务 API token（Bearer，保护 /api/v1/**）
 * @param signingKey 报告签名 HMAC 密钥（仅服务端与 CI 持有）
 * @param signingKeyId 签名密钥标识（轮换用）
 * @param attestRequireKnownCommit 只对服务端已完成评审的 commit 签发（默认 true）
 * @param queueCapacity 有界队列容量（满 = 背压 503）
 * @param workerCount 异步评审 worker 数
 * @param drainTimeoutSeconds 优雅停机时排空队列的等待秒数
 * @param reviewDepth 评审深度档位（quick|standard|deep）
 * @param dryRun dry-run（全程零写；默认 false）
 * @param runRoot trace/brief 落盘根
 * @param workspace 本地工作区（无 checkout 时 pre-existing 检测降级声明，05§2.1）
 * @param githubApiBase GitHub API 根
 * @param githubToken GitHub token（可空 = 公共仓库只读，受速率限制）
 * @param llmApiKey LLM 密钥
 * @param llmBaseUrl LLM 端点
 * @param llmModel standard 档模型
 * @param llmModelStrong strong 档模型（空 = 继承 standard）
 * @param llmModelLight light 档模型（空 = 继承 standard）
 * @param llmTemperature 采样温度（空 = 不传；建议 0.2，05§5）
 * @param llmFallbackChain 降级链（逗号分隔档位）
 * @param llmRpm per-model RPM（形如 standard=60,strong=30,light=60；空 = 默认）
 * @param llmMaxConcurrency LLM 并发信号量上限（CRA_LLM_MAX_CONCURRENCY，默认 8，1..64；有效并发 = min(配置值,
 *     线程池宽度)，09§6）
 * @param crossCheckerEnabled CrossChecker 总开关（默认 false，05§5）
 * @param crossCheckerModes CrossChecker 模式串（assumption,sniff）
 * @param crossCheckerHistoryWeeks 变更史回溯周数
 * @param eventHistoryEnabled 事件史采集定时任务开关（M4f-full，默认 false）
 * @param eventHistoryCron 事件史采集 cron（Spring 六段式）
 * @param eventHistorySource 事件源（本地 JSONL 路径 / file: URI / http(s): URL；M4f-full）
 * @param eventHistoryLookbackDays 单次采集窗口（天）
 * @param eventHistoryOverlapHours 与上次水位的重叠小时数（迟到事件）
 * @param eventHistoryMaxAttempts 读取/入库最大尝试次数（含首次）
 * @param eventHistoryRetryBackoffMillis 重试退避基数（毫秒）
 * @param eventHistoryMaxBytes 事件源单次读取字节上限
 * @param eventHistoryMaxEvents 单次入库事件上限
 * @param runTotalTokens run 级 token 预算（02§4.4，默认 1.6M）
 * @param reviewLargeDiffLines 大 diff 降档线（review.large-diff-lines / CRA_REVIEW_LARGE_DIFF_LINES，默认
 *     PipelineOptions 基准值 4000）
 * @param reviewHardCapLines 硬上限（review.hard-cap-lines / CRA_REVIEW_HARD_CAP_LINES，默认 16000；须 &gt;
 *     large）
 * @param reviewNitBudget nit 预算默认（review.nit-budget-default / CRA_REVIEW_NIT_BUDGET，默认 3，&gt;=0）
 * @param reviewDegradeAtFirst 第一降档阈值（agents.budget.degrade-at[0] / CRA_REVIEW_DEGRADE_AT，默认 0.70）
 * @param reviewDegradeAtSecond 第二降档阈值（agents.budget.degrade-at[1]，默认 0.85）
 * @param reviewSpotCheckPercent important/security 级 REFUTED
 *     抽查保险丝百分比（CRA_REVIEW_SPOT_CHECK_PERCENT， 默认 20，0..100）
 * @param reviewPublishUnverified UNVERIFIED 发布策略（review.publish-unverified /
 *     CRA_REVIEW_PUBLISH_UNVERIFIED：never|labeled，默认 never）
 * @param reviewIncludeTestFiles 测试文件是否进入评审范围（02§4.4 / CRA_REVIEW_TESTS，默认 false）
 * @param semanticContextEnabled 语义上下文（L2 跨文件切片）开关（M4e，ADR-0014；CRA_SEMANTIC_CONTEXT_ENABLED，默认
 *     false——关闭不动基线行为）
 * @param semanticContextMaxFiles 索引扫描 Java 文件数上限（CRA_SEMANTIC_CONTEXT_MAX_FILES，默认 2000）
 * @param semanticContextMaxFileBytes 单文件字节上限（CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES，默认 262144）
 * @param semanticContextSliceTokens 单 brief 切片 token 上限（CRA_SEMANTIC_CONTEXT_SLICE_TOKENS，默认 6000）
 * @param agentBudgets 单代理预算覆盖（05§5 agents.budget.&lt;role&gt;.*；空 = 各定义默认，03§6）
 */
public record ServerConfig(
    String webhookSecret,
    String platform,
    String gitlabBaseUrl,
    String gitlabToken,
    String gitlabBotUsername,
    String apiToken,
    String signingKey,
    String signingKeyId,
    boolean attestRequireKnownCommit,
    int queueCapacity,
    int workerCount,
    int drainTimeoutSeconds,
    String reviewDepth,
    boolean dryRun,
    Path runRoot,
    Path workspace,
    String githubApiBase,
    String githubToken,
    String llmApiKey,
    String llmBaseUrl,
    String llmModel,
    String llmModelStrong,
    String llmModelLight,
    String llmTemperature,
    String llmFallbackChain,
    String llmRpm,
    int llmMaxConcurrency,
    boolean crossCheckerEnabled,
    String crossCheckerModes,
    int crossCheckerHistoryWeeks,
    boolean eventHistoryEnabled,
    String eventHistoryCron,
    String eventHistorySource,
    int eventHistoryLookbackDays,
    int eventHistoryOverlapHours,
    int eventHistoryMaxAttempts,
    long eventHistoryRetryBackoffMillis,
    long eventHistoryMaxBytes,
    int eventHistoryMaxEvents,
    long runTotalTokens,
    int reviewLargeDiffLines,
    int reviewHardCapLines,
    int reviewNitBudget,
    double reviewDegradeAtFirst,
    double reviewDegradeAtSecond,
    int reviewSpotCheckPercent,
    String reviewPublishUnverified,
    boolean reviewIncludeTestFiles,
    boolean semanticContextEnabled,
    int semanticContextMaxFiles,
    long semanticContextMaxFileBytes,
    long semanticContextSliceTokens,
    org.cra.agents.api.AgentBudgetOverrides agentBudgets) {

  public ServerConfig {
    requireText(webhookSecret, "CRA_SERVER_WEBHOOK_SECRET");
    platform = platform == null ? "" : platform.strip();
    if (!"github".equals(platform) && !"gitlab".equals(platform)) {
      throw new CraConfigException(
          "CRA_SERVER_PLATFORM 非法: " + platform + "（github|gitlab，小写；默认 github）");
    }
    if ("gitlab".equals(platform)) {
      requireText(gitlabToken, "CRA_GITLAB_TOKEN");
      gitlabBaseUrl = normalizeGitLabApiBase(gitlabBaseUrl);
    }
    requireText(apiToken, "CRA_SERVER_API_TOKEN");
    requireText(signingKey, "CRA_SERVER_SIGNING_KEY");
    requireText(signingKeyId, "CRA_SERVER_SIGNING_KEY_ID");
    if (queueCapacity <= 0 || queueCapacity > 100_000) {
      throw new CraConfigException("CRA_SERVER_QUEUE_CAPACITY 必须在 1..100000: " + queueCapacity);
    }
    if (workerCount <= 0 || workerCount > 64) {
      throw new CraConfigException("CRA_SERVER_WORKERS 必须在 1..64: " + workerCount);
    }
    if (crossCheckerHistoryWeeks <= 0) {
      throw new CraConfigException(
          "CRA_CROSSCHECKER_HISTORY_WEEKS 必须为正: " + crossCheckerHistoryWeeks);
    }
    if (drainTimeoutSeconds <= 0 || drainTimeoutSeconds > 600) {
      throw new CraConfigException(
          "CRA_SERVER_DRAIN_TIMEOUT_SECONDS 必须在 1..600: " + drainTimeoutSeconds);
    }
    String depth = reviewDepth == null ? "" : reviewDepth.strip().toLowerCase(Locale.ROOT);
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("CRA_SERVER_REVIEW_DEPTH 非法: " + reviewDepth);
    }
    reviewDepth = depth;
    if (runTotalTokens <= 0) {
      throw new CraConfigException("CRA_SERVER_RUN_TOTAL_TOKENS 必须为正: " + runTotalTokens);
    }
    if (llmMaxConcurrency < 1 || llmMaxConcurrency > LlmGatewayConfig.MAX_ALLOWED_CONCURRENCY) {
      throw new CraConfigException(
          "CRA_LLM_MAX_CONCURRENCY 必须在 1.."
              + LlmGatewayConfig.MAX_ALLOWED_CONCURRENCY
              + ": "
              + llmMaxConcurrency);
    }
    if (reviewLargeDiffLines <= 0 || reviewHardCapLines <= reviewLargeDiffLines) {
      throw new CraConfigException(
          "CRA_REVIEW_LARGE_DIFF_LINES / CRA_REVIEW_HARD_CAP_LINES 非法（必须为正且 hard cap > large）: "
              + reviewLargeDiffLines
              + "/"
              + reviewHardCapLines);
    }
    if (reviewNitBudget < 0) {
      throw new CraConfigException("CRA_REVIEW_NIT_BUDGET 必须为非负整数: " + reviewNitBudget);
    }
    if (reviewDegradeAtFirst <= 0
        || reviewDegradeAtSecond <= reviewDegradeAtFirst
        || reviewDegradeAtSecond > 1.0) {
      throw new CraConfigException(
          "CRA_REVIEW_DEGRADE_AT 阈值非法（0 < first < second <= 1）: "
              + reviewDegradeAtFirst
              + ","
              + reviewDegradeAtSecond);
    }
    if (reviewSpotCheckPercent < 0 || reviewSpotCheckPercent > 100) {
      throw new CraConfigException(
          "CRA_REVIEW_SPOT_CHECK_PERCENT 必须在 [0,100]: " + reviewSpotCheckPercent);
    }
    reviewPublishUnverified =
        reviewPublishUnverified == null ? "" : reviewPublishUnverified.strip();
    if (!"never".equals(reviewPublishUnverified) && !"labeled".equals(reviewPublishUnverified)) {
      throw new CraConfigException(
          "CRA_REVIEW_PUBLISH_UNVERIFIED 非法: " + reviewPublishUnverified + "（never|labeled）");
    }
    if (eventHistoryLookbackDays <= 0 || eventHistoryLookbackDays > 365) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_LOOKBACK_DAYS 必须在 1..365: " + eventHistoryLookbackDays);
    }
    if (eventHistoryOverlapHours < 0 || eventHistoryOverlapHours > 720) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_OVERLAP_HOURS 必须在 0..720: " + eventHistoryOverlapHours);
    }
    if (eventHistoryMaxAttempts <= 0 || eventHistoryMaxAttempts > 10) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_MAX_ATTEMPTS 必须在 1..10: " + eventHistoryMaxAttempts);
    }
    if (eventHistoryRetryBackoffMillis < 0 || eventHistoryRetryBackoffMillis > 60_000) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_RETRY_BACKOFF_MS 必须在 0..60000: "
              + eventHistoryRetryBackoffMillis);
    }
    if (eventHistoryMaxBytes <= 0 || eventHistoryMaxBytes > 1_073_741_824L) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_MAX_BYTES 必须在 1..1073741824: " + eventHistoryMaxBytes);
    }
    if (eventHistoryMaxEvents <= 0 || eventHistoryMaxEvents > 100_000) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_MAX_EVENTS 必须在 1..100000: " + eventHistoryMaxEvents);
    }
    eventHistorySource = eventHistorySource == null ? "" : eventHistorySource.strip();
    if (eventHistoryEnabled && eventHistorySource.isBlank()) {
      throw new CraConfigException("CRA_SERVER_EVENT_HISTORY_SOURCE 未设置（事件史采集开启时必须提供 JSONL 来源）");
    }
    if (eventHistoryCron == null || eventHistoryCron.isBlank()) {
      throw new CraConfigException("CRA_SERVER_EVENT_HISTORY_CRON 不能为空");
    }
    agentBudgets =
        agentBudgets == null ? org.cra.agents.api.AgentBudgetOverrides.none() : agentBudgets;
    eventHistoryCron = eventHistoryCron.strip();
    try {
      CronExpression.parse(eventHistoryCron);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException(
          "CRA_SERVER_EVENT_HISTORY_CRON 非法: " + eventHistoryCron + "（" + e.getMessage() + "）");
    }
  }

  /**
   * 从 Spring Environment（application.yml + 环境变量）装配并校验。
   *
   * @param env Spring Environment
   * @return 服务配置
   */
  public static ServerConfig from(Environment env) {
    PipelineOptions defaults = PipelineOptions.defaults();
    DegradeThresholds degradeAt =
        parseDegradeAt(
            env.getProperty("CRA_REVIEW_DEGRADE_AT"),
            defaults.degradeAtFirst(),
            defaults.degradeAtSecond());
    return new ServerConfig(
        env.getProperty("CRA_SERVER_WEBHOOK_SECRET", ""),
        env.getProperty("CRA_SERVER_PLATFORM", "github"),
        env.getProperty("CRA_GITLAB_BASE_URL", "https://gitlab.com/api/v4"),
        firstNonBlank(
            env.getProperty("CRA_GITLAB_TOKEN"),
            LlmParsing.readGitLabTokenFile(env.getProperty("CRA_GITLAB_TOKEN_FILE"))),
        blankToNull(env.getProperty("CRA_GITLAB_BOT_USERNAME")),
        env.getProperty("CRA_SERVER_API_TOKEN", ""),
        env.getProperty("CRA_SERVER_SIGNING_KEY", ""),
        env.getProperty("CRA_SERVER_SIGNING_KEY_ID", "cra-server-v1"),
        bool(env, "CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT", true),
        intValue(env, "CRA_SERVER_QUEUE_CAPACITY", 100),
        intValue(env, "CRA_SERVER_WORKERS", 2),
        intValue(env, "CRA_SERVER_DRAIN_TIMEOUT_SECONDS", 30),
        env.getProperty("CRA_SERVER_REVIEW_DEPTH", "standard"),
        bool(env, "CRA_SERVER_DRY_RUN", false),
        Path.of(env.getProperty("CRA_SERVER_RUN_ROOT", ".cra/server-runs")),
        Path.of(env.getProperty("CRA_SERVER_WORKSPACE", ".")),
        env.getProperty("CRA_GITHUB_API_BASE", "https://api.github.com"),
        firstNonBlank(env.getProperty("CRA_GITHUB_TOKEN"), env.getProperty("GITHUB_TOKEN")),
        env.getProperty("CRA_LLM_API_KEY", ""),
        env.getProperty("CRA_LLM_BASE_URL", ""),
        env.getProperty("CRA_LLM_MODEL", ""),
        blankToNull(env.getProperty("CRA_LLM_MODEL_STRONG")),
        blankToNull(env.getProperty("CRA_LLM_MODEL_LIGHT")),
        blankToNull(env.getProperty("CRA_LLM_TEMPERATURE")),
        env.getProperty("CRA_LLM_FALLBACK_CHAIN", "standard,light"),
        env.getProperty("CRA_LLM_RPM", ""),
        intValue(env, "CRA_LLM_MAX_CONCURRENCY", LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY),
        bool(env, "CRA_CROSSCHECKER_ENABLED", false),
        env.getProperty("CRA_CROSSCHECKER_MODES", "assumption,sniff"),
        intValue(env, "CRA_CROSSCHECKER_HISTORY_WEEKS", 8),
        bool(env, "CRA_SERVER_EVENT_HISTORY_ENABLED", false),
        env.getProperty("CRA_SERVER_EVENT_HISTORY_CRON", "0 0 3 * * *"),
        env.getProperty("CRA_SERVER_EVENT_HISTORY_SOURCE", ""),
        intValue(env, "CRA_SERVER_EVENT_HISTORY_LOOKBACK_DAYS", 7),
        intValue(env, "CRA_SERVER_EVENT_HISTORY_OVERLAP_HOURS", 24),
        intValue(env, "CRA_SERVER_EVENT_HISTORY_MAX_ATTEMPTS", 3),
        longValue(env, "CRA_SERVER_EVENT_HISTORY_RETRY_BACKOFF_MS", 500),
        longValue(env, "CRA_SERVER_EVENT_HISTORY_MAX_BYTES", 67_108_864L),
        intValue(env, "CRA_SERVER_EVENT_HISTORY_MAX_EVENTS", 5_000),
        longValue(env, "CRA_SERVER_RUN_TOTAL_TOKENS", PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS),
        intValue(env, "CRA_REVIEW_LARGE_DIFF_LINES", defaults.largeDiffLines()),
        intValue(env, "CRA_REVIEW_HARD_CAP_LINES", defaults.hardCapLines()),
        intValue(env, "CRA_REVIEW_NIT_BUDGET", defaults.nitBudgetDefault()),
        degradeAt.first(),
        degradeAt.second(),
        intValue(env, "CRA_REVIEW_SPOT_CHECK_PERCENT", defaults.importantRefutedSpotCheckPercent()),
        firstNonBlank(
            env.getProperty("CRA_REVIEW_PUBLISH_UNVERIFIED"), defaults.publishUnverified()),
        bool(env, "CRA_REVIEW_TESTS", defaults.includeTestFiles()),
        bool(env, "CRA_SEMANTIC_CONTEXT_ENABLED", false),
        intValue(env, "CRA_SEMANTIC_CONTEXT_MAX_FILES", 2_000),
        longValue(env, "CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES", 262_144L),
        longValue(
            env, "CRA_SEMANTIC_CONTEXT_SLICE_TOKENS", SemanticContextOptions.DEFAULT_SLICE_TOKENS),
        agentBudgetOverrides(env));
  }

  /**
   * 单代理预算覆盖（05§5 {@code agents.budget.<role>.*}；环境变量形如 {@code
   * CRA_AGENTS_BUDGET_VERIFIER_INPUT_TOKENS}）。非法值失败于启动期（fail fast）。键构建公共体见 {@link
   * LlmParsing#agentBudgetValues}（R4：与 CLI 同语义收敛）。
   *
   * @param env Spring Environment
   * @return 覆盖（无配置时为空覆盖）
   */
  static org.cra.agents.api.AgentBudgetOverrides agentBudgetOverrides(Environment env) {
    Map<String, String> values =
        LlmParsing.agentBudgetValues(
            org.cra.agents.api.AgentBudgetOverrides.CONFIGURABLE_ROLES,
            (flag, envKey) -> env.getProperty(envKey));
    try {
      return org.cra.agents.api.AgentBudgetOverrides.fromConfig(values);
    } catch (IllegalArgumentException e) {
      throw new CraConfigException("agents.budget 非法: " + e.getMessage());
    }
  }

  /**
   * 是否 GitLab 平台（M4e 第二半）。
   *
   * @return true = GitLab
   */
  public boolean isGitlab() {
    return "gitlab".equals(platform);
  }

  /**
   * API 根归一：接受实例根或已含 `/api/v4` 的地址（与 CLI 同语义；公共体见 {@link LlmParsing#normalizeGitLabApiBase}）。
   *
   * @param raw 用户输入
   * @return 以 `/api/v4` 结尾且无尾随 `/` 的 API 根
   */
  static String normalizeGitLabApiBase(String raw) {
    return LlmParsing.normalizeGitLabApiBase(raw, "https://gitlab.com/api/v4");
  }

  /**
   * CrossChecker 模式解析（非法值组装期拒绝；公共体见 {@link LlmParsing#parseCsvSet}）。
   *
   * @return 模式集合
   */
  public Set<CrossCheckerOptions.Mode> parsedCrossCheckerModes() {
    return LlmParsing.parseCsvSet(
        crossCheckerModes,
        CrossCheckerOptions.Mode::parse,
        (raw, e) -> new CraConfigException(e.getMessage()),
        "CRA_CROSSCHECKER_MODES 不能为空（assumption|sniff）");
  }

  /**
   * 降级链解析（逗号分隔档位名；公共体见 {@link LlmParsing#parseFallbackChain}）。
   *
   * @return 档位列表
   */
  public List<ModelTier> parsedFallbackChain() {
    return List.copyOf(LlmParsing.parseFallbackChain(llmFallbackChain, "CRA_LLM_FALLBACK_CHAIN"));
  }

  /**
   * per-model RPM 解析（空 = 05§5 默认值；公共体见 {@link LlmParsing#parseRpm}）。
   *
   * @param defaultRpm 默认 RPM 表
   * @return 档位 → RPM
   */
  public Map<ModelTier, Integer> parsedRpm(Map<ModelTier, Integer> defaultRpm) {
    return LlmParsing.parseRpm(llmRpm, "CRA_LLM_RPM", defaultRpm);
  }

  /**
   * 采样温度解析（公共体见 {@link LlmParsing#parseTemperature}）。
   *
   * @return 0.0–2.0；null = 不传
   */
  public Double parsedTemperature() {
    return LlmParsing.parseTemperature(llmTemperature, "CRA_LLM_TEMPERATURE");
  }

  /**
   * 降级阈值（0 &lt; first &lt; second &lt;= 1；未配置/空白取 PipelineOptions 基准值）。 与 CLI 的 {@code
   * PlatformArgs.degradeAt} 逐条同语义（空白=默认、恰两段、区间校验）；尾逗号视为空段忽略（与 fallback-chain/RPM
   * 的“空段跳过”约定一致），中间空段/多余段按段数拒绝。 server 侧只在 env 装配期出现，错误前缀保留 CRA_REVIEW_DEGRADE_AT key 名。
   */
  private record DegradeThresholds(double first, double second) {}

  private static DegradeThresholds parseDegradeAt(
      String value, double defaultFirst, double defaultSecond) {
    if (value == null || value.isBlank()) {
      return new DegradeThresholds(defaultFirst, defaultSecond);
    }
    // split(",") 默认丢弃尾部空段：\"0.70,0.85,\" 与 \"0.70,0.85\" 等价；\",0.70,0.85\" / \"0.70,,0.85\" 段数 >2
    // 拒绝
    String[] parts = value.split(",");
    if (parts.length != 2) {
      throw new CraConfigException("CRA_REVIEW_DEGRADE_AT 形如 0.70,0.85: " + value);
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
      return new DegradeThresholds(first, second);
    } catch (NumberFormatException e) {
      throw new CraConfigException("CRA_REVIEW_DEGRADE_AT 阈值非法（0 < first < second <= 1）: " + value);
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new CraConfigException(name + " 未设置（fail closed：服务不在缺失密钥时启动）");
    }
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static int intValue(Environment env, String key, int dflt) {
    String raw = env.getProperty(key);
    if (raw == null || raw.isBlank()) {
      return dflt;
    }
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为整数: " + raw);
    }
  }

  private static long longValue(Environment env, String key, long dflt) {
    String raw = env.getProperty(key);
    if (raw == null || raw.isBlank()) {
      return dflt;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为整数: " + raw);
    }
  }

  private static boolean bool(Environment env, String key, boolean dflt) {
    String raw = env.getProperty(key);
    if (raw == null || raw.isBlank()) {
      return dflt;
    }
    String value = raw.strip().toLowerCase(Locale.ROOT);
    if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) {
      return true;
    }
    if ("false".equals(value) || "0".equals(value) || "no".equals(value)) {
      return false;
    }
    throw new CraConfigException(key + " 必须为布尔值: " + raw);
  }
}
