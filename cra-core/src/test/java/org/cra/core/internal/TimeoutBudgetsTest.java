package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.cra.llm.api.ModelTier;
import org.junit.jupiter.api.Test;

/** 外层 backstop 推导（09§6）：不得短于网关最坏重试链；未知配置回退 720s。 */
class TimeoutBudgetsTest {

  @Test
  void derivesBackstopFromGatewayRetryBudget() {
    LlmGateway gateway = configured(120_000L, 3);
    // 120s × (3+1) + 60s = 540s，高于 360s 下限
    assertThat(TimeoutBudgets.agentBackstopMs(gateway)).isEqualTo(540_000L);
  }

  @Test
  void keepsHistoricalFloorForShortConfiguredChains() {
    LlmGateway gateway = configured(10_000L, 1);
    // 10s × 2 + 60s = 80s → 仍不得低于 360s
    assertThat(TimeoutBudgets.agentBackstopMs(gateway)).isEqualTo(360_000L);
  }

  @Test
  void fallsBackTo720sWhenGatewayHasNoConfig() {
    LlmGateway unknown =
        new LlmGateway() {
          @Override
          public LlmResponse complete(LlmRequest request) {
            return LlmResponse.unavailable("n/a", 0, 0);
          }
        };
    assertThat(TimeoutBudgets.agentBackstopMs(unknown)).isEqualTo(720_000L);
    assertThat(TimeoutBudgets.agentBackstopMs(null)).isEqualTo(720_000L);
  }

  @Test
  void verificationBackstopAddsOneReducedContextRetry() {
    assertThat(TimeoutBudgets.verificationBackstopMs(configured(120_000L, 3)))
        .isEqualTo(1_080_000L);
  }

  private static LlmGateway configured(long requestTimeoutMs, int maxRetries) {
    LlmGatewayConfig config =
        new LlmGatewayConfig(
            LlmGatewayConfig.models(
                new LlmGatewayConfig.ModelEndpoint("m", "http://llm.test/v1/", "k"),
                new LlmGatewayConfig.ModelEndpoint("m", "http://llm.test/v1/", "k"),
                new LlmGatewayConfig.ModelEndpoint("m", "http://llm.test/v1/", "k")),
            List.of(ModelTier.STANDARD),
            LlmGatewayConfig.defaultRpm(),
            1,
            maxRetries,
            1_000L,
            requestTimeoutMs,
            1_000L,
            8_000L,
            null,
            Map.of(),
            null);
    return new LlmGateway() {
      @Override
      public LlmResponse complete(LlmRequest request) {
        return LlmResponse.ok("{}", 0, 0, 0, "m");
      }

      @Override
      public Optional<LlmGatewayConfig> config() {
        return Optional.of(config);
      }
    };
  }
}
