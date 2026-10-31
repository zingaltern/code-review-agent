package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.PipelineOptions;
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
  void reviewPolicyDefaultsUnchanged() {
    // 05§5 review.*：sweep 缺省值必须与 PipelineOptions 基准一致（不改变既有夜间行为）
    CraConfig config = SweepArgs.parse(baseArgs(), ENV).config();
    PipelineOptions defaults = PipelineOptions.defaults();
    assertThat(config.largeDiffLines()).isEqualTo(defaults.largeDiffLines());
    assertThat(config.hardCapLines()).isEqualTo(defaults.hardCapLines());
    assertThat(config.nitBudget()).isEqualTo(defaults.nitBudgetDefault());
    assertThat(config.degradeAtFirst()).isEqualTo(defaults.degradeAtFirst());
    assertThat(config.degradeAtSecond()).isEqualTo(defaults.degradeAtSecond());
    assertThat(config.publishUnverified()).isEqualTo(defaults.publishUnverified());
    assertThat(config.spotCheckPercent()).isEqualTo(defaults.importantRefutedSpotCheckPercent());
    assertThat(config.includeTestFiles()).isEqualTo(defaults.includeTestFiles());
  }

  @Test
  void reviewPolicyEnvOverridesApply() {
    Map<String, String> env = new HashMap<>(ENV);
    env.put("CRA_REVIEW_LARGE_DIFF_LINES", "1000");
    env.put("CRA_REVIEW_HARD_CAP_LINES", "5000");
    env.put("CRA_REVIEW_NIT_BUDGET", "0");
    env.put("CRA_REVIEW_DEGRADE_AT", "0.60,0.80");
    env.put("CRA_REVIEW_SPOT_CHECK_PERCENT", "100");
    env.put("CRA_REVIEW_PUBLISH_UNVERIFIED", "labeled");
    env.put("CRA_REVIEW_TESTS", "true");
    CraConfig config = SweepArgs.parse(baseArgs(), env).config();
    assertThat(config.largeDiffLines()).isEqualTo(1_000);
    assertThat(config.hardCapLines()).isEqualTo(5_000);
    assertThat(config.nitBudget()).isZero();
    assertThat(config.degradeAtFirst()).isEqualTo(0.60);
    assertThat(config.degradeAtSecond()).isEqualTo(0.80);
    assertThat(config.spotCheckPercent()).isEqualTo(100);
    assertThat(config.publishUnverified()).isEqualTo("labeled");
    assertThat(config.includeTestFiles()).isTrue();
  }

  @Test
  void reviewPolicyRejectsInvalidValues() {
    assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_LARGE_DIFF_LINES", "0")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_LARGE_DIFF_LINES");
    assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_HARD_CAP_LINES", "4000")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_HARD_CAP_LINES");
    assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_NIT_BUDGET", "-1")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_NIT_BUDGET");
    assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_DEGRADE_AT", "0.9,0.8")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_DEGRADE_AT");
    assertThatThrownBy(
            () -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_SPOT_CHECK_PERCENT", "101")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_SPOT_CHECK_PERCENT");
    assertThatThrownBy(
            () -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_PUBLISH_UNVERIFIED", "always")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_PUBLISH_UNVERIFIED");
    assertThatThrownBy(() -> SweepArgs.parse(baseArgs(), with("CRA_REVIEW_TESTS", "maybe")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_REVIEW_TESTS");
  }

  @Test
  void blankReviewEnvFallsBackToDefaults() {
    Map<String, String> env = new HashMap<>(ENV);
    env.put("CRA_REVIEW_LARGE_DIFF_LINES", "");
    env.put("CRA_REVIEW_DEGRADE_AT", "  ");
    env.put("CRA_REVIEW_PUBLISH_UNVERIFIED", "");
    CraConfig config = SweepArgs.parse(baseArgs(), env).config();
    assertThat(config.largeDiffLines()).isEqualTo(PipelineOptions.DEFAULT_LARGE_DIFF_LINES);
    assertThat(config.degradeAtFirst()).isEqualTo(0.70);
    assertThat(config.publishUnverified()).isEqualTo("never");
  }

  private static Map<String, String> with(String key, String value) {
    Map<String, String> env = new HashMap<>(ENV);
    env.put(key, value);
    return env;
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
