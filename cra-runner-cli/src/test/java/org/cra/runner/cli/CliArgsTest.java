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

  // ---------- T2.10：本地 pre-PR 模式（05§3 本地形态） ----------

  private static final Map<String, String> EMPTY_ENV = Map.of();

  @Test
  void localModeDefaults() {
    // --local 一无所有也能解析：repo/head/token/LLM 全部转为可选（命令执行期解析或如实跳过）
    CraConfig config = CliArgs.parse(new String[] {"--local"}, EMPTY_ENV);
    assertThat(config.local()).isTrue();
    assertThat(config.repoOwner()).isNull();
    assertThat(config.prNumber()).isZero();
    assertThat(config.baseSha()).isEqualTo("main");
    assertThat(config.headSha()).isNull();
    assertThat(config.dryRun()).isTrue(); // 本地默认 dry-run（05§3）
    assertThat(config.execCommand()).isEqualTo(CliArgs.DEFAULT_EXEC);
    assertThat(config.ledgerDb()).contains("/.cra/ledger.db").doesNotContain("~");
    assertThat(config.llmApiKey()).isNull();
    assertThat(config.fallbackChain()).isEqualTo("standard,light");
    assertThat(config.rpm()).isEmpty();
  }

  @Test
  void localModeExplicitOverrides() {
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--local",
              "--base",
              "develop",
              "--exec",
              "gradle check",
              "--ledger-db",
              "/tmp/l.db",
              "--pr",
              "42",
              "--dry-run=false",
              "--model-strong",
              "glm-strong",
              "--model-light",
              "glm-lite",
              "--fallback-chain",
              "standard,strong",
              "--rpm",
              "standard=10,strong=5"
            },
            EMPTY_ENV);
    assertThat(config.baseSha()).isEqualTo("develop");
    assertThat(config.execCommand()).isEqualTo("gradle check");
    assertThat(config.ledgerDb()).isEqualTo("/tmp/l.db");
    assertThat(config.prNumber()).isEqualTo(42);
    assertThat(config.dryRun()).isFalse();
    assertThat(config.llmModelStrong()).isEqualTo("glm-strong");
    assertThat(config.llmModelLight()).isEqualTo("glm-lite");
    assertThat(config.fallbackChain()).isEqualTo("standard,strong");
    assertThat(config.rpm()).isEqualTo("standard=10,strong=5");
  }

  @Test
  void crossCheckerDefaultsOffAndParsesConfig() {
    // 05§5 agents.crosschecker：默认关闭；显式开关 + 模式 + 历史周数
    CraConfig defaults = CliArgs.parse(baseArgs(), ENV);
    assertThat(defaults.crossCheckerEnabled()).isFalse();
    assertThat(defaults.crossCheckerModes()).isEqualTo("assumption,sniff");
    assertThat(defaults.crossCheckerHistoryWeeks()).isEqualTo(8);

    CraConfig enabled =
        CliArgs.parse(
            concat(
                baseArgs(),
                "--crosschecker",
                "--crosschecker-modes",
                "sniff",
                "--crosschecker-history-weeks",
                "12"),
            ENV);
    assertThat(enabled.crossCheckerEnabled()).isTrue();
    assertThat(enabled.crossCheckerModes()).isEqualTo("sniff");
    assertThat(enabled.crossCheckerHistoryWeeks()).isEqualTo(12);

    // 环境变量形态（CI 配置面）
    CraConfig fromEnv =
        CliArgs.parse(
            baseArgs(),
            Map.of(
                "GITHUB_TOKEN", "gh-env-token",
                "CRA_LLM_API_KEY", "sk-env",
                "CRA_LLM_BASE_URL", "https://llm.example/v1",
                "CRA_LLM_MODEL", "glm-x",
                "CRA_CROSSCHECKER_ENABLED", "true"));
    assertThat(fromEnv.crossCheckerEnabled()).isTrue();
  }

  @Test
  void blankModelEnvInheritsStandard() {
    // GitHub Actions 空 input 会以 "" 注入 env：语义必须等同未设置（缺省继承 standard），
    // 否则 ModelEndpoint 拒绝 blank → 启动期 CraConfigException（eval workflow 默认档曾全崩）。
    CraConfig config =
        CliArgs.parse(
            baseArgs(),
            Map.of(
                "GITHUB_TOKEN", "gh-env-token",
                "CRA_LLM_API_KEY", "sk-env",
                "CRA_LLM_BASE_URL", "https://llm.example/v1",
                "CRA_LLM_MODEL", "glm-x",
                "CRA_LLM_MODEL_STRONG", "",
                "CRA_LLM_MODEL_LIGHT", "  "));
    assertThat(config.llmModelStrong()).isNull();
    assertThat(config.llmModelLight()).isNull();
  }

  @Test
  void crossCheckerRejectsInvalidModesAndWeeks() {
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    concat(baseArgs(), "--crosschecker", "--crosschecker-modes", "bad"), ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--crosschecker-modes");
    assertThatThrownBy(
            () -> CliArgs.parse(concat(baseArgs(), "--crosschecker-history-weeks", "0"), ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("crosschecker-history-weeks");
  }

  @Test
  void signoffDefaultsAndExplicitOptions() {
    // 05§5 agents.signoff（M4a）：默认开启；作者回退 GITHUB_ACTOR；测试状态默认 unknown
    java.util.Map<String, String> env = new java.util.HashMap<>(ENV);
    env.put("GITHUB_ACTOR", "octocat");
    CraConfig defaults = CliArgs.parse(baseArgs(), env);
    assertThat(defaults.signoff().enabled()).isTrue();
    assertThat(defaults.signoff().author()).isEqualTo("octocat");
    assertThat(defaults.signoff().trustedAuthors()).isEmpty();
    assertThat(defaults.signoff().testsStatus()).isEqualTo("unknown");

    CraConfig explicit =
        CliArgs.parse(
            concat(
                baseArgs(),
                "--signoff=false",
                "--trusted-authors",
                "alice, bob ,",
                "--pr-author",
                "carol",
                "--tests-status",
                "passed"),
            env);
    assertThat(explicit.signoff().enabled()).isFalse();
    assertThat(explicit.signoff().trustedAuthors()).containsExactly("alice", "bob");
    assertThat(explicit.signoff().author()).isEqualTo("carol");
    assertThat(explicit.signoff().testsStatus()).isEqualTo("passed");
    assertThat(explicit.signoff().context().testsStatus())
        .isEqualTo(org.cra.common.signoff.TestsStatus.PASSED);
  }

  @Test
  void signoffOptionsFromEnvAndInvalidTestsStatus() {
    java.util.Map<String, String> env = new java.util.HashMap<>(ENV);
    env.put("CRA_SIGNOFF_ENABLED", "false");
    env.put("CRA_SIGNOFF_TRUSTED_AUTHORS", "zed");
    env.put("CRA_PR_AUTHOR", "dana");
    env.put("CRA_TESTS_STATUS", "failed");
    CraConfig config = CliArgs.parse(baseArgs(), env);
    assertThat(config.signoff().enabled()).isFalse();
    assertThat(config.signoff().trustedAuthors()).containsExactly("zed");
    assertThat(config.signoff().author()).isEqualTo("dana");
    assertThat(config.signoff().testsStatus()).isEqualTo("failed");

    assertThatThrownBy(() -> CliArgs.parse(concat(baseArgs(), "--tests-status", "green"), ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--tests-status");
  }

  @Test
  void ciModeStillRequiresCoreArgs() {
    // CI 形态回归：--local 之外缺 repo/pr/head/token 仍拒绝启动（LLM 缺失由 missingLlmKeyFails 覆盖）
    assertThatThrownBy(() -> CliArgs.parse(new String[] {}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--pr");
    assertThatThrownBy(() -> CliArgs.parse(baseArgs(), EMPTY_ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--token");
    assertThatThrownBy(
            () -> CliArgs.parse(new String[] {"--local", "--repo", "bad-format"}, EMPTY_ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("owner/name");
  }

  private static String[] concat(String[] a, String... b) {
    String[] out = new String[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
