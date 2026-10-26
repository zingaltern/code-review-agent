package org.cra.llm.api;

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
 * @param queueTimeoutMs 信号量排队超时（超时该端点失败并试降级链下一端点，02§4.5）
 * @param requestTimeoutMs 单次请求超时
 * @param backoffBaseMs 指数退避基值（第 n 次重试等待 base * 2^(n-1)，封顶 backoffMaxMs）
 * @param backoffMaxMs 退避封顶
 * @param temperature 采样温度（null = 不传，沿用 provider 默认；评审/验证/仲裁为分类判断任务， 推荐 0.2 降低运行间方差——M3
 *     方差的直接技术根源，05§5 CRA_LLM_TEMPERATURE）
 * @param extraHeaders 附加请求头（v1.3.2：base-url 指向 opencode 时自动附加 x-opencode-session—— opencode-go
 *     的路由要求；其他端点为空 map）
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
    Map<String, String> extraHeaders) {

  /** 并发上限默认值（02§4.5）。 */
  public static final int DEFAULT_MAX_CONCURRENCY = 8;

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
    if (!models.containsKey(ModelTier.STANDARD)) {
      throw new IllegalArgumentException("models 必须至少配置 standard 档（05§5）");
    }
    for (ModelTier tier : fallbackChain) {
      if (!models.containsKey(tier)) {
        throw new IllegalArgumentException("fallback-chain 条目未配置模型端点: " + tier);
      }
    }
    for (Integer rpm : perModelRpm.values()) {
      if (rpm == null || rpm <= 0) {
        throw new IllegalArgumentException("per-model RPM 必须为正整数");
      }
    }
    if (maxConcurrency <= 0 || maxRetries < 0) {
      throw new IllegalArgumentException("maxConcurrency/maxRetries must be positive");
    }
    if (queueTimeoutMs <= 0
        || requestTimeoutMs <= 0
        || backoffBaseMs <= 0
        || backoffMaxMs < backoffBaseMs) {
      throw new IllegalArgumentException("timeouts must be positive and backoff capped");
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
        Map.of());
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
