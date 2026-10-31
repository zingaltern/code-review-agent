package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.llm.api.ModelTier;
import org.junit.jupiter.api.Test;

/** T2.9 组合根侧：CLI/环境值 → 三档路由配置（05§5）。 */
class LlmConfigFactoryTest {

  private static CraConfig config(String fallbackChain, String rpm, String strong, String light) {
    return CraConfig.builder()
        .repoOwner("o")
        .repoName("r")
        .prNumber(1)
        .baseSha("a")
        .headSha("b")
        .depth("standard")
        .githubToken("t")
        .githubApi("api")
        .llmApiKey("sk")
        .llmBaseUrl("https://llm.example/v1")
        .llmModel("glm-std")
        .runRoot(".")
        .workspace(".")
        .largeDiffLines(2000)
        .hardCapLines(8000)
        .nitBudget(3)
        .runTotalTokens(500_000L)
        .degradeAtFirst(0.70)
        .degradeAtSecond(0.85)
        .publishUnverified("never")
        .spotCheckPercent(20)
        .execCommand("mvn -B -ntp verify")
        .ledgerDb("~/.cra/ledger.db")
        .llmModelStrong(strong)
        .llmModelLight(light)
        .fallbackChain(fallbackChain)
        .rpm(rpm)
        .build();
  }

  @Test
  void buildsThreeTiersWithInheritance() {
    var gateway = LlmConfigFactory.create(config("standard,light", null, "glm-strong", null));
    assertThat(gateway.models().get(ModelTier.STANDARD).model()).isEqualTo("glm-std");
    assertThat(gateway.models().get(ModelTier.STRONG).model()).isEqualTo("glm-strong");
    assertThat(gateway.models().get(ModelTier.LIGHT).model()).isEqualTo("glm-std"); // 继承 standard
    assertThat(gateway.fallbackChain()).containsExactly(ModelTier.STANDARD, ModelTier.LIGHT);
    assertThat(gateway.rpmOf(ModelTier.STANDARD)).isEqualTo(60); // 默认 05§5 per-model-rpm
    assertThat(gateway.rpmOf(ModelTier.STRONG)).isEqualTo(30);
    assertThat(gateway.rpmOf(ModelTier.LIGHT)).isEqualTo(60);
    // 未配置的档位不限流
    assertThat(gateway.rpmOf(ModelTier.STRONG)).isPositive();
  }

  @Test
  void cliMaxConcurrencyEnvReachesGateway() {
    // 05§5 max-concurrency：CRA_LLM_MAX_CONCURRENCY → CraConfig → LlmGatewayConfig（组合根全链）
    java.util.Map<String, String> env =
        Map.of(
            "GITHUB_TOKEN", "t",
            "CRA_LLM_API_KEY", "sk",
            "CRA_LLM_BASE_URL", "https://llm.example/v1",
            "CRA_LLM_MODEL", "glm-std",
            "CRA_LLM_MAX_CONCURRENCY", "4");
    var gateway =
        LlmConfigFactory.create(
            CliArgs.parse(
                new String[] {"--repo", "o/r", "--pr", "1", "--base", "a", "--head", "b"}, env));
    assertThat(gateway.maxConcurrency()).isEqualTo(4);
  }

  @Test
  void blankModelsInheritStandard() {
    // 直接构造 CraConfig 的调用方（非 CLI 解析）也必须享受"空白 = 继承"语义
    var gateway = LlmConfigFactory.create(config("standard,light", null, "", "  "));
    assertThat(gateway.models().get(ModelTier.STRONG).model()).isEqualTo("glm-std");
    assertThat(gateway.models().get(ModelTier.LIGHT).model()).isEqualTo("glm-std");
  }

  @Test
  void opencodeBaseUrlGetsAutomaticSessionHeader() {
    // v1.3.2 opencode-go 适配：base-url 指向 opencode 时自动附加 x-opencode-session
    // （opencode-go 的路由要求；缺失时返回 MissingSessionID）
    var gateway =
        new LlmConfigFactoryTestSupport().createWithBaseUrl("https://opencode.ai/zen/go/v1");
    org.assertj.core.api.Assertions.assertThat(gateway.extraHeaders())
        .containsKey("x-opencode-session");
    org.assertj.core.api.Assertions.assertThat(gateway.extraHeaders().get("x-opencode-session"))
        .isNotBlank();

    var plain = new LlmConfigFactoryTestSupport().createWithBaseUrl("https://api.deepseek.com");
    org.assertj.core.api.Assertions.assertThat(plain.extraHeaders()).isEmpty();
    // opencode 端点自动关闭 thinking（reasoning token 计入 max_tokens）
    org.assertj.core.api.Assertions.assertThat(gateway.reasoningEffort()).isEqualTo("none");
    org.assertj.core.api.Assertions.assertThat(plain.reasoningEffort()).isNull();
  }

  @Test
  void parsesFallbackChainAndRpm() {
    assertThat(LlmConfigFactory.parseFallbackChain("standard,strong,light"))
        .containsExactly(ModelTier.STANDARD, ModelTier.STRONG, ModelTier.LIGHT);
    assertThat(LlmConfigFactory.parseFallbackChain("")).isEmpty();
    assertThat(LlmConfigFactory.parseRpm("standard=10,strong=5"))
        .isEqualTo(Map.of(ModelTier.STANDARD, 10, ModelTier.STRONG, 5));
    assertThat(LlmConfigFactory.parseRpm(""))
        .isEqualTo(org.cra.llm.api.LlmGatewayConfig.defaultRpm());
  }

  @Test
  void rejectsInvalidChainAndRpm() {
    assertThatThrownBy(() -> LlmConfigFactory.parseFallbackChain("standard,ultra"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("ultra");
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard=60,mega=10"))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard=0"))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard"))
        .isInstanceOf(CraConfigException.class);
  }

  @Test
  void rpmInvalidEntriesKeepCliKeyNamesInMessages() {
    // R4 文案参数化：CLI 侧错误前缀保留 "--rpm" key 名（server 侧对应 CRA_LLM_RPM，见 ServerConfigPlatformTest）
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--rpm 条目形如 standard=60: standard");
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard=60,mega=10"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--rpm 条目非法")
        .hasMessageContaining("mega=10");
    assertThatThrownBy(() -> LlmConfigFactory.parseRpm("standard=0"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--rpm 条目非法");
  }

  /** 测试辅助：用指定 baseUrl 构造网关（其余沿用既有 config 默认；回填走 toBuilder）。 */
  static final class LlmConfigFactoryTestSupport {
    org.cra.llm.api.LlmGatewayConfig createWithBaseUrl(String baseUrl) {
      return LlmConfigFactory.create(
          config("standard,light", null, null, null).toBuilder().llmBaseUrl(baseUrl).build());
    }
  }
}
