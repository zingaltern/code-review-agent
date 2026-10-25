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
