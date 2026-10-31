package org.cra.llm.api;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 网关配置（05§5 cra.llm；T2.9 升级为三档路由形态，02§4.5）。值来源 = 配置文件与环境变量， 由组合根装配；非法配置在组装期拒绝（硬校验，05§5
 * agents.verifier 同款纪律）。
 *
 * @param models 三档模型端点（档位 → 端点；至少配置 standard）
 * @param fallbackChain 降级链（02§4.2：主模型 → 备用模型；请求档位先试，链内其余依序兜底； 条目必须已在 models 中配置）
 * @param perModelRpm 每模型 RPM 限制（05§5 per-model-rpm，默认 standard=60/strong=30/light=60）
 * @param maxConcurrency 并发信号量上限（全局，默认 8，02§4.5）
 * @param maxRetries 429/5xx/超时单端点最大重试次数（默认 3，02§4.5）
 * @param queueTimeoutMs 排队等待预算（并发许可与 RPM 槽等待共用该值；超时视为未触达模型，该端点失败并试降级链下一端点，02§4.5）
 * @param requestTimeoutMs 单次请求超时
 * @param backoffBaseMs 指数退避基值（第 n 次重试等待 base * 2^(n-1)，封顶 backoffMaxMs）
 * @param backoffMaxMs 退避封顶
 * @param temperature 采样温度（null = 不传，沿用 provider 默认；评审/验证/仲裁为分类判断任务， 推荐 0.2 降低运行间方差——M3
 *     方差的直接技术根源，05§5 CRA_LLM_TEMPERATURE）
 * @param extraHeaders 附加请求头（v1.3.2：base-url 指向 opencode 时自动附加 x-opencode-session—— opencode-go
 *     的路由要求；其他端点为空 map）
 * @param reasoningEffort 推理力度（null = 不传；opencode 端点自动 none——reasoning token 计入 max_tokens， 评审任务的
 *     1–2k 输出预算会被推理吃光，冒烟实测 budget_exceeded）
 */
public record LlmGatewayConfig(
    Map<ModelTier, ModelEndpoint> models,
    List<ModelTier> fallbackChain,
    Map<ModelTier, Integer> perModelRpm,
    int maxConcurrency,
    int maxRetries,
    long queueTimeoutMs,
    long requestTimeoutMs,
    long backoffBaseMs,
    long backoffMaxMs,
    Double temperature,
    Map<String, String> extraHeaders,
    String reasoningEffort) {

  /** 并发上限默认值（02§4.5）。 */
  public static final int DEFAULT_MAX_CONCURRENCY = 8;

  /**
   * 并发上限的配置面允许最大值（05§5 {@code CRA_LLM_MAX_CONCURRENCY} 校验上限）：更宽的值不会提升实际并发——评审/验证受 cra-core
   * 固定线程池（8/4，09§6）约束，有效并发 = min(配置值, 线程池宽度)。
   */
  public static final int MAX_ALLOWED_CONCURRENCY = 64;

  /** 重试次数默认值（02§4.5：最多 3 次）。 */
  public static final int DEFAULT_MAX_RETRIES = 3;

  /** per-model RPM 默认值（05§5 per-model-rpm {standard: 60, strong: 30, light: 60}）。 */
  public static final int DEFAULT_RPM_STANDARD = 60;

  /** per-model RPM 默认值（同上）。 */
  public static final int DEFAULT_RPM_STRONG = 30;

  /** per-model RPM 默认值（同上）。 */
  public static final int DEFAULT_RPM_LIGHT = 60;

  public LlmGatewayConfig {
    models = models == null ? Map.of() : Map.copyOf(models);
    fallbackChain = List.copyOf(fallbackChain == null ? List.of() : fallbackChain);
    perModelRpm = perModelRpm == null ? Map.of() : Map.copyOf(perModelRpm);
    validateModels(models, fallbackChain);
    validatePerModelRpm(perModelRpm);
    validateConcurrency(maxConcurrency, maxRetries);
    validateTimeouts(queueTimeoutMs, requestTimeoutMs, backoffBaseMs, backoffMaxMs);
    extraHeaders = normalizeExtraHeaders(extraHeaders);
  }

  /**
   * 校验模型表与降级链（05§5：至少 standard；链内条目必须已配置端点）。
   *
   * @param models 档位 → 端点
   * @param fallbackChain 降级链
   */
  private static void validateModels(
      Map<ModelTier, ModelEndpoint> models, List<ModelTier> fallbackChain) {
    if (!models.containsKey(ModelTier.STANDARD)) {
      throw new IllegalArgumentException("models 必须至少配置 standard 档（05§5）");
    }
    for (ModelTier tier : fallbackChain) {
      if (!models.containsKey(tier)) {
        throw new IllegalArgumentException("fallback-chain 条目未配置模型端点: " + tier);
      }
    }
  }

  /**
   * 校验 per-model RPM（05§5：必须为正整数）。
   *
   * @param perModelRpm 档位 → RPM
   */
  private static void validatePerModelRpm(Map<ModelTier, Integer> perModelRpm) {
    for (Integer rpm : perModelRpm.values()) {
      if (rpm == null || rpm <= 0) {
        throw new IllegalArgumentException("per-model RPM 必须为正整数");
      }
    }
  }

  /**
   * 校验并发与重试限值（02§4.5）。
   *
   * @param maxConcurrency 并发上限
   * @param maxRetries 重试次数
   */
  private static void validateConcurrency(int maxConcurrency, int maxRetries) {
    if (maxConcurrency <= 0 || maxRetries < 0) {
      throw new IllegalArgumentException(
          "maxConcurrency must be positive, maxRetries must be non-negative");
    }
  }

  /**
   * 校验超时与退避限值（02§4.5）。
   *
   * @param queueTimeoutMs 排队预算
   * @param requestTimeoutMs 请求超时
   * @param backoffBaseMs 退避基值
   * @param backoffMaxMs 退避封顶
   */
  private static void validateTimeouts(
      long queueTimeoutMs, long requestTimeoutMs, long backoffBaseMs, long backoffMaxMs) {
    if (queueTimeoutMs <= 0
        || requestTimeoutMs <= 0
        || backoffBaseMs <= 0
        || backoffMaxMs < backoffBaseMs) {
      throw new IllegalArgumentException("timeouts must be positive and backoff capped");
    }
  }

  /**
   * 校验并防御拷贝附加请求头（09§6：外部 Map 后续变更不影响已装配配置）。
   *
   * @param extraHeaders 原始附加头（可空）
   * @return 不可变副本（null 归为空 map）
   */
  private static Map<String, String> normalizeExtraHeaders(Map<String, String> extraHeaders) {
    if (extraHeaders == null) {
      return Map.of();
    }
    for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
      requireHeader(header.getKey(), "附加头名");
      requireHeader(header.getValue(), "附加头值");
    }
    return Map.copyOf(extraHeaders);
  }

  /** 请求头必须在组装期可被 JDK HttpClient 接受（运行期抛 IAE 会穿出网关边界，09§4）。 */
  private static void requireHeader(String value, String what) {
    if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
      throw new IllegalArgumentException(what + " 非法: " + value);
    }
  }

  /**
   * 单档端点（05§5 models.* 条目）。
   *
   * @param model 模型名（OpenAI 兼容 chat/completions 的 model 字段）
   * @param baseUrl 端点根地址（未单独配置时继承 standard 的 base-url）
   * @param apiKey API 密钥（只从环境变量读，05§6；未单独配置时继承 standard）
   */
  public record ModelEndpoint(String model, String baseUrl, String apiKey) {
    public ModelEndpoint {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(baseUrl, "baseUrl");
      Objects.requireNonNull(apiKey, "apiKey");
      if (model.isBlank() || baseUrl.isBlank()) {
        throw new IllegalArgumentException("model/baseUrl must be non-blank");
      }
      // 组装期拒绝非法 URL（运行期 URI.create 抛 IAE 会穿出网关；09§4 / 05§5 fail-fast）
      try {
        URI uri = URI.create(baseUrl);
        String scheme = uri.getScheme();
        if (scheme == null
            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            || uri.getHost() == null) {
          throw new IllegalArgumentException("baseUrl 必须为 http(s) 绝对地址: " + baseUrl);
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("baseUrl 非法: " + baseUrl, e);
      }
    }
  }

  /**
   * 生产默认参数构造（M0 形态兼容：单端点三档共用、降级链 [standard]、默认 RPM）。
   *
   * @param baseUrl 端点根地址
   * @param model 模型名
   * @param apiKey API 密钥
   * @return 默认并发/重试/超时/RPM 参数的配置
   */
  public static LlmGatewayConfig ofProduction(String baseUrl, String model, String apiKey) {
    ModelEndpoint endpoint = new ModelEndpoint(model, baseUrl, apiKey);
    return new LlmGatewayConfig(
        models(endpoint, endpoint, endpoint),
        List.of(ModelTier.STANDARD),
        defaultRpm(),
        DEFAULT_MAX_CONCURRENCY,
        DEFAULT_MAX_RETRIES,
        30_000,
        120_000,
        1_000,
        8_000,
        null,
        Map.of(),
        null);
  }

  /**
   * 三档模型表（未单独配置的档位继承 standard 端点——05§5 中仅 standard 显式带 base-url， 其余档位默认同端点同密钥、可按档位覆写模型名）。
   *
   * @param standard standard 端点
   * @param strong strong 端点
   * @param light light 端点
   * @return 档位 → 端点
   */
  public static Map<ModelTier, ModelEndpoint> models(
      ModelEndpoint standard, ModelEndpoint strong, ModelEndpoint light) {
    Map<ModelTier, ModelEndpoint> map = new EnumMap<>(ModelTier.class);
    map.put(ModelTier.STANDARD, standard);
    map.put(ModelTier.STRONG, strong);
    map.put(ModelTier.LIGHT, light);
    return map;
  }

  /**
   * 默认 RPM 表（05§5）。
   *
   * @return 档位 → RPM
   */
  public static Map<ModelTier, Integer> defaultRpm() {
    Map<ModelTier, Integer> map = new EnumMap<>(ModelTier.class);
    map.put(ModelTier.STANDARD, DEFAULT_RPM_STANDARD);
    map.put(ModelTier.STRONG, DEFAULT_RPM_STRONG);
    map.put(ModelTier.LIGHT, DEFAULT_RPM_LIGHT);
    return map;
  }

  /**
   * 解析某档位的 RPM 上限（未配置的档位不限制，返回 0）。
   *
   * @param tier 档位
   * @return RPM（0 = 不限）
   */
  public int rpmOf(ModelTier tier) {
    return perModelRpm.getOrDefault(tier, 0);
  }
}
