package org.cra.runner.cli;

import java.util.List;
import java.util.Map;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmParsing;
import org.cra.llm.api.ModelTier;

/**
 * LLM 路由配置装配（T2.9 组合根侧）：CraConfig（CLI 参数 + 环境变量）→ {@link LlmGatewayConfig} 三档形态。 未单独配置的档位继承
 * standard 端点（同 base-url 同密钥，仅模型名可异——05§5 中仅 standard 显式带 base-url）。 非法值组装期拒绝（CraConfigException →
 * 退出码 2）。 解析与三档装配的公共体在 {@link LlmParsing}（R4：与 cra-server 组合根同语义收敛），本类只做 CLI 值形态的适配。
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
    return LlmParsing.threeTierConfig(
        config.llmBaseUrl(),
        config.llmApiKey(),
        config.llmModel(),
        config.llmModelStrong(),
        config.llmModelLight(),
        parseFallbackChain(config.fallbackChain()),
        parseRpm(config.rpm()),
        LlmParsing.parseTemperature(config.llmTemperature(), "CRA_LLM_TEMPERATURE"),
        config.llmMaxConcurrency());
  }

  /** 降级链解析（05§5 fallback-chain；CLI 侧 key 名前缀，公共体见 {@link LlmParsing#parseFallbackChain}）。 */
  static List<ModelTier> parseFallbackChain(String value) {
    return LlmParsing.parseFallbackChain(value, "fallback-chain");
  }

  /** per-model RPM 解析（05§5；CLI 侧 key 名前缀，公共体见 {@link LlmParsing#parseRpm}）。 */
  static Map<ModelTier, Integer> parseRpm(String value) {
    return LlmParsing.parseRpm(value, "--rpm", LlmGatewayConfig.defaultRpm());
  }
}
