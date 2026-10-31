package org.cra.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cra.common.exception.CraConfigException;
import org.cra.llm.api.LlmGatewayConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** CRA_LLM_MAX_CONCURRENCY（05§5 max-concurrency）：server 组合根读取与 1..64 组装期校验。 */
class ServerConfigLlmTest {

  @Test
  void maxConcurrencyDefaultsAndOverride() {
    assertThat(ServerConfig.from(baseEnv()).llmMaxConcurrency())
        .isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);

    MockEnvironment override = baseEnv();
    override.setProperty("CRA_LLM_MAX_CONCURRENCY", "16");
    assertThat(ServerConfig.from(override).llmMaxConcurrency()).isEqualTo(16);
  }

  @Test
  void maxConcurrencyRejectsOutOfRangeAtStartup() {
    for (String invalid : new String[] {"0", "65"}) {
      MockEnvironment env = baseEnv();
      env.setProperty("CRA_LLM_MAX_CONCURRENCY", invalid);
      assertThatThrownBy(() -> ServerConfig.from(env))
          .isInstanceOf(CraConfigException.class)
          .hasMessageContaining("CRA_LLM_MAX_CONCURRENCY 必须在 1..64");
    }
    MockEnvironment nonNumeric = baseEnv();
    nonNumeric.setProperty("CRA_LLM_MAX_CONCURRENCY", "abc");
    assertThatThrownBy(() -> ServerConfig.from(nonNumeric))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_MAX_CONCURRENCY 必须为整数");
  }

  private static MockEnvironment baseEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("CRA_SERVER_WEBHOOK_SECRET", "wh-secret");
    env.setProperty("CRA_SERVER_API_TOKEN", "api-token");
    env.setProperty("CRA_SERVER_SIGNING_KEY", "signing-key");
    env.setProperty("CRA_LLM_API_KEY", "sk");
    env.setProperty("CRA_LLM_BASE_URL", "http://127.0.0.1:1/v1");
    env.setProperty("CRA_LLM_MODEL", "m");
    return env;
  }
}
