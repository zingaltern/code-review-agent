package org.cra.server.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.llm.api.ModelTier;
import org.springframework.core.env.Environment;

/**
 * cra-server 运行配置（05§5 部署级配置的服务形态子集；密钥只从环境变量读，05§6）。
 *
 * <p>组装期硬校验：webhook 密钥 / API token / 签名密钥缺失即拒绝启动（fail closed）。评审参数沿用 05§5 默认值，仅暴露 run 级预算与深度档位；LLM
 * 三档路由与 CrossChecker 开关与 CLI 同语义。
 *
 * @param webhookSecret GitHub webhook HMAC 密钥（X-Hub-Signature-256）
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
 * @param crossCheckerEnabled CrossChecker 总开关（默认 false，05§5）
 * @param crossCheckerModes CrossChecker 模式串（assumption,sniff）
 * @param crossCheckerHistoryWeeks 变更史回溯周数
 * @param eventHistoryEnabled 事件史采集定时任务开关（M4f-full 骨架，默认 false）
 * @param eventHistoryCron 事件史采集 cron（Spring 六段式）
 * @param runTotalTokens run 级 token 预算（02§4.4，默认 500k）
 */
public record ServerConfig(
    String webhookSecret,
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
    boolean crossCheckerEnabled,
    String crossCheckerModes,
    int crossCheckerHistoryWeeks,
    boolean eventHistoryEnabled,
    String eventHistoryCron,
    long runTotalTokens) {

  public ServerConfig {
    requireText(webhookSecret, "CRA_SERVER_WEBHOOK_SECRET");
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
  }

  /**
   * 从 Spring Environment（application.yml + 环境变量）装配并校验。
   *
   * @param env Spring Environment
   * @return 服务配置
   */
  public static ServerConfig from(Environment env) {
    return new ServerConfig(
        env.getProperty("CRA_SERVER_WEBHOOK_SECRET", ""),
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
        bool(env, "CRA_CROSSCHECKER_ENABLED", false),
        env.getProperty("CRA_CROSSCHECKER_MODES", "assumption,sniff"),
        intValue(env, "CRA_CROSSCHECKER_HISTORY_WEEKS", 8),
        bool(env, "CRA_SERVER_EVENT_HISTORY_ENABLED", false),
        env.getProperty("CRA_SERVER_EVENT_HISTORY_CRON", "0 0 3 * * *"),
        longValue(env, "CRA_SERVER_RUN_TOTAL_TOKENS", 500_000L));
  }

  /**
   * CrossChecker 模式解析（非法值组装期拒绝）。
   *
   * @return 模式集合
   */
  public Set<CrossCheckerOptions.Mode> parsedCrossCheckerModes() {
    Set<CrossCheckerOptions.Mode> modes = new LinkedHashSet<>();
    for (String part : crossCheckerModes.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        modes.add(CrossCheckerOptions.Mode.parse(part));
      } catch (IllegalArgumentException e) {
        throw new CraConfigException(e.getMessage());
      }
    }
    if (modes.isEmpty()) {
      throw new CraConfigException("CRA_CROSSCHECKER_MODES 不能为空（assumption|sniff）");
    }
    return Set.copyOf(modes);
  }

  /**
   * 降级链解析（逗号分隔档位名）。
   *
   * @return 档位列表
   */
  public List<ModelTier> parsedFallbackChain() {
    List<ModelTier> chain = new ArrayList<>();
    for (String part : llmFallbackChain.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        chain.add(ModelTier.valueOf(part.strip().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("CRA_LLM_FALLBACK_CHAIN 档位非法: " + part);
      }
    }
    return List.copyOf(chain);
  }

  /**
   * per-model RPM 解析（空 = 05§5 默认值）。
   *
   * @param defaultRpm 默认 RPM 表
   * @return 档位 → RPM
   */
  public Map<ModelTier, Integer> parsedRpm(Map<ModelTier, Integer> defaultRpm) {
    if (llmRpm == null || llmRpm.isBlank()) {
      return defaultRpm;
    }
    Map<ModelTier, Integer> rpm = new EnumMap<>(ModelTier.class);
    for (String part : llmRpm.split(",")) {
      String entry = part.strip();
      if (entry.isEmpty()) {
        continue;
      }
      int eq = entry.indexOf('=');
      if (eq <= 0 || eq == entry.length() - 1) {
        throw new CraConfigException("CRA_LLM_RPM 条目形如 standard=60: " + entry);
      }
      try {
        ModelTier tier = ModelTier.valueOf(entry.substring(0, eq).strip().toUpperCase(Locale.ROOT));
        int value = Integer.parseInt(entry.substring(eq + 1).strip());
        if (value <= 0) {
          throw new NumberFormatException();
        }
        rpm.put(tier, value);
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("CRA_LLM_RPM 条目非法: " + entry);
      }
    }
    return rpm.isEmpty() ? defaultRpm : Map.copyOf(rpm);
  }

  /**
   * 采样温度解析。
   *
   * @return 0.0–2.0；null = 不传
   */
  public Double parsedTemperature() {
    if (llmTemperature == null || llmTemperature.isBlank()) {
      return null;
    }
    try {
      double value = Double.parseDouble(llmTemperature.strip());
      if (value < 0 || value > 2) {
        throw new NumberFormatException();
      }
      return value;
    } catch (NumberFormatException e) {
      throw new CraConfigException("CRA_LLM_TEMPERATURE 非法（0..2）: " + llmTemperature);
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
