package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;

/** 05§3 CLI 契约：参数解析（参数优先于环境变量；密钥只认环境变量；缺项拒绝启动）。 */
class CliArgsTest {

  private static final Map<String, String> ENV =
      Map.of(
          "GITHUB_TOKEN", "gh-env-token",
          "CRA_LLM_API_KEY", "sk-env",
          "CRA_LLM_BASE_URL", "https://llm.example/v1",
          "CRA_LLM_MODEL", "glm-x");

  private static String[] baseArgs() {
    return new String[] {"--repo", "o/r", "--pr", "7", "--base", "abc", "--head", "def"};
  }

  @Test
  void m1ReviewAndBudgetOptionsWithDefaults() {
    // 05§5 review.* 与 agents.budget：缺省即文档基准值
    CraConfig config = CliArgs.parse(baseArgs(), ENV);
    assertThat(config.largeDiffLines()).isEqualTo(2000);
    assertThat(config.hardCapLines()).isEqualTo(8000);
    assertThat(config.nitBudget()).isEqualTo(3);
    assertThat(config.runTotalTokens()).isEqualTo(500_000L);
    assertThat(config.degradeAtFirst()).isEqualTo(0.70);
    assertThat(config.degradeAtSecond()).isEqualTo(0.85);
    assertThat(config.publishUnverified()).isEqualTo("never");
    assertThat(config.workspace()).isEqualTo(".");
  }

  @Test
  void m1OptionsExplicitlySet() {
    String[] args =
        concat(
            baseArgs(),
            "--large-diff-lines",
            "3000",
            "--hard-cap-lines",
            "9000",
            "--nit-budget",
            "0",
            "--run-total-tokens",
            "400000",
            "--degrade-at",
            "0.60,0.80",
            "--publish-unverified",
            "labeled",
            "--workspace",
            "/repo/ws");
    CraConfig config = CliArgs.parse(args, ENV);
    assertThat(config.largeDiffLines()).isEqualTo(3000);
    assertThat(config.hardCapLines()).isEqualTo(9000);
    assertThat(config.nitBudget()).isZero();
    assertThat(config.runTotalTokens()).isEqualTo(400_000L);
    assertThat(config.degradeAtFirst()).isEqualTo(0.60);
    assertThat(config.degradeAtSecond()).isEqualTo(0.80);
    assertThat(config.publishUnverified()).isEqualTo("labeled");
    assertThat(config.workspace()).isEqualTo("/repo/ws");
  }

  @Test
  void m1OptionsRejectInvalidValues() {
    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--degrade-at", "0.9,0.8"), ENV))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--degrade-at", "1.2,0.9"), ENV))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--nit-budget", "-1"), ENV))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(
            () -> CliArgs.parse(concat(baseArgs(), "--publish-unverified", "always"), ENV))
        .isInstanceOf(CraConfigException.class);
  }

  @Test
  void parsesArgsWithEnvFallback() {
    CraConfig config = CliArgs.parse(baseArgs(), ENV);
    assertThat(config.repoOwner()).isEqualTo("o");
    assertThat(config.repoName()).isEqualTo("r");
    assertThat(config.prNumber()).isEqualTo(7);
    assertThat(config.depth()).isEqualTo("standard");
    assertThat(config.dryRun()).isFalse();
    assertThat(config.githubToken()).isEqualTo("gh-env-token"); // 参数缺省 → 环境变量
    assertThat(config.llmModel()).isEqualTo("glm-x");
    assertThat(config.runRoot()).isEqualTo(".cra/runs");
  }

  @Test
  void explicitTokenOverridesEnv() {
    String[] args = concat(baseArgs(), "--token", "gh-arg", "--dry-run", "--security-block");
    CraConfig config = CliArgs.parse(args, ENV);
    assertThat(config.githubToken()).isEqualTo("gh-arg");
    assertThat(config.dryRun()).isTrue();
    assertThat(config.securityBlock()).isTrue();
  }

  @Test
  void depthValidation() {
    String[] args = concat(baseArgs(), "--depth", "quick");
    assertThat(CliArgs.parse(args, ENV).depth()).isEqualTo("quick");
    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--depth", "ultra"), ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("depth");
  }

  @Test
  void missingPrFails() {
    assertThatThrownBy(() -> CliArgs.parse(new String[] {"--repo", "o/r"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--pr");
  }

  @Test
  void missingLlmKeyFails() {
    Map<String, String> noLlm = Map.of("GITHUB_TOKEN", "gh-token");
    assertThatThrownBy(() -> CliArgs.parse(baseArgs(), noLlm))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_API_KEY");
  }

  @Test
  void unknownArgAndBadPrRejected() {
    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--wat"), ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("未知参数");
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {"--repo", "o/r", "--pr", "x", "--base", "a", "--head", "b"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--pr");
  }

  private static String[] concat(String[] a, String... b) {
    String[] out = new String[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
