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
    return new CraConfig(
        "o",
        "r",
        1,
        "a",
        "b",
        "standard",
        false,
        false,
        "t",
        "api",
        "sk",
        "https://llm.example/v1",
        "glm-std",
        ".",
        ".",
        2000,
        8000,
        3,
        500_000L,
        0.70,
        0.85,
        "never",
        20,
        false,
        "mvn -B -ntp verify",
        "~/.cra/ledger.db",
        strong,
        light,
        fallbackChain,
        rpm);
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

  /** 测试辅助：用指定 baseUrl 构造网关（其余沿用既有 config 默认）。 */
  static final class LlmConfigFactoryTestSupport {
    org.cra.llm.api.LlmGatewayConfig createWithBaseUrl(String baseUrl) {
      CraConfig base = config("standard,light", null, null, null);
      CraConfig withUrl =
          new CraConfig(
              base.repoOwner(),
              base.repoName(),
              base.prNumber(),
              base.baseSha(),
              base.headSha(),
              base.depth(),
              base.dryRun(),
              base.securityBlock(),
              base.githubToken(),
              base.githubApi(),
              base.llmApiKey(),
              baseUrl,
              base.llmModel(),
              base.runRoot(),
              base.workspace(),
              base.largeDiffLines(),
              base.hardCapLines(),
              base.nitBudget(),
              base.runTotalTokens(),
              base.degradeAtFirst(),
              base.degradeAtSecond(),
              base.publishUnverified(),
              base.spotCheckPercent(),
              base.local(),
              base.execCommand(),
              base.ledgerDb(),
              base.llmModelStrong(),
              base.llmModelLight(),
              base.fallbackChain(),
              base.rpm(),
              base.crossCheckerEnabled(),
              base.crossCheckerModes(),
              base.crossCheckerHistoryWeeks(),
              base.llmTemperature());
      return LlmConfigFactory.create(withUrl);
    }
  }
}
