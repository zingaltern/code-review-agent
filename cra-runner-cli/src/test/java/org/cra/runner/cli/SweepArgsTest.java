package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.llm.api.LlmGatewayConfig;
import org.junit.jupiter.api.Test;

/** `cra sweep` 的 LLM 并发配置（05§5 max-concurrency；环境变量形态，与 review 同源）。 */
class SweepArgsTest {

  private static final Map<String, String> ENV =
      Map.of(
          "GITHUB_TOKEN", "gh-token",
          "CRA_LLM_API_KEY", "sk-env",
          "CRA_LLM_BASE_URL", "https://llm.example/v1",
          "CRA_LLM_MODEL", "glm-x");

  /** sweep 的最小必填参数（仓库标识；token/LLM 走 env）。 */
  private static String[] baseArgs() {
    return new String[] {"--repo", "o/r"};
  }

  @Test
  void llmMaxConcurrencyDefaultsAndEnvOverride() {
    assertThat(SweepArgs.parse(baseArgs(), ENV).config().llmMaxConcurrency())
        .isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);

    Map<String, String> env = new HashMap<>(ENV);
    env.put("CRA_LLM_MAX_CONCURRENCY", "16");
    assertThat(SweepArgs.parse(baseArgs(), env).config().llmMaxConcurrency()).isEqualTo(16);
  }

  @Test
  void llmMaxConcurrencyRejectsOutOfRange() {
    for (String invalid : new String[] {"0", "65"}) {
      Map<String, String> env = new HashMap<>(ENV);
      env.put("CRA_LLM_MAX_CONCURRENCY", invalid);
      assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), env))
          .isInstanceOf(CraConfigException.class)
          .hasMessageContaining("CRA_LLM_MAX_CONCURRENCY 必须在 1..64");
    }
  }
}
