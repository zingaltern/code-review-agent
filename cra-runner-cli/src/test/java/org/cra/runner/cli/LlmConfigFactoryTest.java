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
}
