package org.cra.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cra.common.exception.CraConfigException;
import org.cra.core.api.PipelineOptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 05§5 review.* 七项（服务端形态）：CRA_REVIEW_* 读取、默认值单源与组装期校验。 */
class ServerConfigReviewOptionsTest {

  @Test
  void defaultsComeFromPipelineOptions() {
    PipelineOptions defaults = PipelineOptions.defaults();
    ServerConfig config = ServerConfig.from(baseEnv());
    assertThat(config.reviewLargeDiffLines()).isEqualTo(defaults.largeDiffLines());
    assertThat(config.reviewHardCapLines()).isEqualTo(defaults.hardCapLines());
    assertThat(config.reviewNitBudget()).isEqualTo(defaults.nitBudgetDefault());
    assertThat(config.reviewDegradeAtFirst()).isEqualTo(defaults.degradeAtFirst());
    assertThat(config.reviewDegradeAtSecond()).isEqualTo(defaults.degradeAtSecond());
    assertThat(config.reviewSpotCheckPercent())
        .isEqualTo(defaults.importantRefutedSpotCheckPercent());
    assertThat(config.reviewPublishUnverified()).isEqualTo(defaults.publishUnverified());
    assertThat(config.reviewIncludeTestFiles()).isEqualTo(defaults.includeTestFiles());

    // 默认配置下服务端 PipelineOptions 与基准值完全一致（行为不变）
    assertThat(new ServerBeans().pipelineOptions(config)).isEqualTo(defaults);
  }

  @Test
  void envOverridesAllReviewOptions() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_REVIEW_LARGE_DIFF_LINES", "1000");
    env.setProperty("CRA_REVIEW_HARD_CAP_LINES", "5000");
    env.setProperty("CRA_REVIEW_NIT_BUDGET", "0");
    env.setProperty("CRA_REVIEW_DEGRADE_AT", "0.60,0.80");
    env.setProperty("CRA_REVIEW_SPOT_CHECK_PERCENT", "100");
    env.setProperty("CRA_REVIEW_PUBLISH_UNVERIFIED", "labeled");
    env.setProperty("CRA_REVIEW_TESTS", "true");

    ServerConfig config = ServerConfig.from(env);
    PipelineOptions options = new ServerBeans().pipelineOptions(config);
    assertThat(options.largeDiffLines()).isEqualTo(1_000);
    assertThat(options.hardCapLines()).isEqualTo(5_000);
    assertThat(options.nitBudgetDefault()).isZero();
    assertThat(options.degradeAtFirst()).isEqualTo(0.60);
    assertThat(options.degradeAtSecond()).isEqualTo(0.80);
    assertThat(options.importantRefutedSpotCheckPercent()).isEqualTo(100);
    assertThat(options.publishUnverified()).isEqualTo("labeled");
    assertThat(options.includeTestFiles()).isTrue();
    assertThat(options.runTotalTokens()).isEqualTo(PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS);
  }

  @Test
  void boundaryValuesAreAccepted() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_REVIEW_LARGE_DIFF_LINES", "1");
    env.setProperty("CRA_REVIEW_HARD_CAP_LINES", "2");
    env.setProperty("CRA_REVIEW_NIT_BUDGET", "0");
    env.setProperty("CRA_REVIEW_SPOT_CHECK_PERCENT", "0");
    ServerConfig config = ServerConfig.from(env);
    assertThat(config.reviewLargeDiffLines()).isEqualTo(1);
    assertThat(config.reviewHardCapLines()).isEqualTo(2);
  }

  @Test
  void invalidReviewOptionsFailAtStartup() {
    assertRejected(
        "CRA_REVIEW_LARGE_DIFF_LINES",
        "0",
        "CRA_REVIEW_LARGE_DIFF_LINES / CRA_REVIEW_HARD_CAP_LINES");
    assertRejected(
        "CRA_REVIEW_HARD_CAP_LINES",
        "4000",
        "CRA_REVIEW_LARGE_DIFF_LINES / CRA_REVIEW_HARD_CAP_LINES");
    assertRejected("CRA_REVIEW_NIT_BUDGET", "-1", "CRA_REVIEW_NIT_BUDGET");
    assertRejected("CRA_REVIEW_DEGRADE_AT", "0.9,0.8", "CRA_REVIEW_DEGRADE_AT");
    assertRejected("CRA_REVIEW_DEGRADE_AT", "1.2,0.9", "CRA_REVIEW_DEGRADE_AT");
    assertRejected("CRA_REVIEW_SPOT_CHECK_PERCENT", "101", "CRA_REVIEW_SPOT_CHECK_PERCENT");
    assertRejected("CRA_REVIEW_PUBLISH_UNVERIFIED", "always", "CRA_REVIEW_PUBLISH_UNVERIFIED");
    assertRejected("CRA_REVIEW_TESTS", "maybe", "CRA_REVIEW_TESTS");
    assertRejected("CRA_REVIEW_LARGE_DIFF_LINES", "abc", "CRA_REVIEW_LARGE_DIFF_LINES");
  }

  @Test
  void blankReviewEnvFallsBackToDefaults() {
    // GitHub Actions 空 input 会以 "" 注入 env：空白 = 未设置
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_REVIEW_LARGE_DIFF_LINES", "");
    env.setProperty("CRA_REVIEW_PUBLISH_UNVERIFIED", "  ");
    ServerConfig config = ServerConfig.from(env);
    assertThat(config.reviewLargeDiffLines()).isEqualTo(PipelineOptions.DEFAULT_LARGE_DIFF_LINES);
    assertThat(config.reviewPublishUnverified()).isEqualTo("never");
  }

  private static void assertRejected(String key, String value, String messagePart) {
    MockEnvironment env = baseEnv();
    env.setProperty(key, value);
    assertThatThrownBy(() -> ServerConfig.from(env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining(messagePart);
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
