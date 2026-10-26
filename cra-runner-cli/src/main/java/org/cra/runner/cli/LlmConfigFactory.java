package org.cra.runner.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmGatewayConfig.ModelEndpoint;
import org.cra.llm.api.ModelTier;

/**
 * LLM 路由配置装配（T2.9 组合根侧）：CraConfig（CLI 参数 + 环境变量）→ {@link LlmGatewayConfig} 三档形态。 未单独配置的档位继承
 * standard 端点（同 base-url 同密钥，仅模型名可异——05§5 中仅 standard 显式带 base-url）。 非法值组装期拒绝（CraConfigException →
 * 退出码 2）。
 */
final class LlmConfigFactory {

  private LlmConfigFactory() {}

  /**
   * 装配三档路由配置。
   *
   * @param config 运行配置（llmBaseUrl/llmApiKey/llmModel 须已非空——CI 在 CliArgs 强制，本地在命令执行期校验）
   * @return 网关配置
   */
  static LlmGatewayConfig create(CraConfig config) {
    ModelEndpoint standard =
        new ModelEndpoint(config.llmModel(), config.llmBaseUrl(), config.llmApiKey());
    ModelEndpoint strong =
        new ModelEndpoint(
            modelOrInherit(config.llmModelStrong(), config.llmModel()),
            config.llmBaseUrl(),
            config.llmApiKey());
    ModelEndpoint light =
        new ModelEndpoint(
            modelOrInherit(config.llmModelLight(), config.llmModel()),
            config.llmBaseUrl(),
            config.llmApiKey());
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(standard, strong, light),
        parseFallbackChain(config.fallbackChain()),
        parseRpm(config.rpm()),
        LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY,
        LlmGatewayConfig.DEFAULT_MAX_RETRIES,
        30_000,
        120_000,
        1_000,
        8_000,
        parseTemperature(config.llmTemperature()));
  }

  /** 档位模型名缺省继承 standard；空白串等同未配置（ModelEndpoint 拒绝 blank）。 */
  private static String modelOrInherit(String model, String standard) {
    return model == null || model.isBlank() ? standard : model;
  }

  /** 降级链解析（05§5 fallback-chain；逗号分隔档位名，空 = 无兜底）。 */
  static List<ModelTier> parseFallbackChain(String value) {
    List<ModelTier> chain = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return chain;
    }
    for (String part : value.split(",")) {
      String name = part.strip();
      if (name.isEmpty()) {
        continue;
      }
      try {
        chain.add(ModelTier.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("fallback-chain 档位非法: " + name + "（standard|strong|light）");
      }
    }
    return chain;
  }

  /** per-model RPM 解析（05§5 per-model-rpm；形如 standard=60,strong=30,light=60，空 = 文档默认值）。 */
  static Map<ModelTier, Integer> parseRpm(String value) {
    if (value == null || value.isBlank()) {
      return LlmGatewayConfig.defaultRpm();
    }
    Map<ModelTier, Integer> rpm = new java.util.EnumMap<>(ModelTier.class);
    for (String part : value.split(",")) {
      String entry = part.strip();
      if (entry.isEmpty()) {
        continue;
      }
      int eq = entry.indexOf('=');
      if (eq <= 0 || eq == entry.length() - 1) {
        throw new CraConfigException("--rpm 条目形如 standard=60: " + entry);
      }
      String tierName = entry.substring(0, eq).strip();
      String number = entry.substring(eq + 1).strip();
      try {
        ModelTier tier = ModelTier.valueOf(tierName.toUpperCase(Locale.ROOT));
        int parsed = Integer.parseInt(number);
        if (parsed <= 0) {
          throw new NumberFormatException();
        }
        rpm.put(tier, parsed);
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("--rpm 条目非法（档位 standard|strong|light，正整数 RPM）: " + entry);
      }
    }
    return rpm;
  }

  /**
   * 采样温度解析（05§5 CRA_LLM_TEMPERATURE；空/未配置 = null 不传，沿用 provider 默认）。
   *
   * @param value 原始值（可为 null/空白）
   * @return 0.0–2.0 的 Double；null 表示不传
   */
  private static Double parseTemperature(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      double t = Double.parseDouble(value.strip());
      if (t < 0 || t > 2) {
        throw new CraConfigException("CRA_LLM_TEMPERATURE 越界: " + value + "（0.0–2.0）");
      }
      return t;
    } catch (NumberFormatException e) {
      throw new CraConfigException("CRA_LLM_TEMPERATURE 非法: " + value);
    }
  }
}
