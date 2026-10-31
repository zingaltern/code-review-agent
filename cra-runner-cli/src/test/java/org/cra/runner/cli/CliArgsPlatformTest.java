package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M4e 第二半：CLI 平台选择与 GitLab 配置解析（ADR-0013；默认 github 保持兼容）。 */
class CliArgsPlatformTest {

  @TempDir Path tempDir;

  private static Map<String, String> baseEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("GITHUB_TOKEN", "gh-token");
    env.put("CRA_LLM_API_KEY", "sk-env");
    env.put("CRA_LLM_BASE_URL", "https://llm.example/v1");
    env.put("CRA_LLM_MODEL", "glm-x");
    return env;
  }

  @Test
  void defaultsToGithubAndKeepsExistingEnvFallbacks() {
    CraConfig config =
        CliArgs.parse(
            new String[] {"--repo", "o/r", "--pr", "7", "--base", "abc", "--head", "def"},
            baseEnv());
    assertThat(config.platform()).isEqualTo("github");
    assertThat(config.isGitlab()).isFalse();
    assertThat(config.githubToken()).isEqualTo("gh-token");
    assertThat(config.gitlab().token()).isNull();
  }

  @Test
  void platformEnvSelectsGitlab() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GIT_PLATFORM", "gitlab");
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--gitlab-project", "group/proj", "--pr", "7", "--base", "b", "--head", "h"
            },
            env);
    assertThat(config.platform()).isEqualTo("gitlab");
    assertThat(config.isGitlab()).isTrue();
    assertThat(config.repoOwner()).isEqualTo("group");
    assertThat(config.repoName()).isEqualTo("proj");
    assertThat(config.gitlab().token()).isEqualTo("glpat-env");
  }

  @Test
  void gitlabCiVariablesFillRepoPrBaseHeadAndToken() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GIT_PLATFORM", "gitlab");
    env.put("CRA_GITLAB_TOKEN", "glpat-ci");
    env.put("CI_PROJECT_PATH", "group/subgroup/proj");
    env.put("CI_MERGE_REQUEST_IID", "12");
    env.put("CI_MERGE_REQUEST_DIFF_BASE_SHA", "base-sha");
    env.put("CI_COMMIT_SHA", "head-sha");
    CraConfig config = CliArgs.parse(new String[] {"--platform", "gitlab"}, env);
    assertThat(config.repoOwner()).isEqualTo("group/subgroup");
    assertThat(config.repoName()).isEqualTo("proj");
    assertThat(config.prNumber()).isEqualTo(12);
    assertThat(config.baseSha()).isEqualTo("base-sha");
    assertThat(config.headSha()).isEqualTo("head-sha");
  }

  @Test
  void gitlabProjectFlagOverridesCiProjectPath() {
    Map<String, String> env = baseEnv();
    env.put("CI_PROJECT_PATH", "ignored/project");
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--platform",
              "gitlab",
              "--gitlab-project",
              "explicit/proj",
              "--pr",
              "1",
              "--base",
              "b",
              "--head",
              "h"
            },
            withToken(env));
    assertThat(config.repoOwner()).isEqualTo("explicit");
    assertThat(config.repoName()).isEqualTo("proj");
  }

  @Test
  void gitlabBaseUrlNormalizesInstanceRootToApiV4() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_BASE_URL", "https://gitlab.example.com/");
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--platform",
              "gitlab",
              "--gitlab-project",
              "g/p",
              "--pr",
              "1",
              "--base",
              "b",
              "--head",
              "h"
            },
            withToken(env));
    assertThat(config.gitlab().baseUrl()).isEqualTo("https://gitlab.example.com/api/v4");
  }

  @Test
  void gitlabTokenFromFileIsTrimmed() throws Exception {
    Path tokenFile = tempDir.resolve("gitlab-token");
    Files.writeString(tokenFile, "  glpat-file\n");
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN_FILE", tokenFile.toString());
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--platform",
              "gitlab",
              "--gitlab-project",
              "g/p",
              "--pr",
              "1",
              "--base",
              "b",
              "--head",
              "h"
            },
            env);
    assertThat(config.gitlab().token()).isEqualTo("glpat-file");
  }

  @Test
  void platformFlagIsCaseInsensitive() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--platform",
              "GitLab",
              "--gitlab-project",
              "g/p",
              "--pr",
              "1",
              "--base",
              "b",
              "--head",
              "h"
            },
            env);
    assertThat(config.platform()).isEqualTo("gitlab");
  }

  @Test
  void missingGitlabTokenFileFailsAsConfig() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN_FILE", tempDir.resolve("missing").toString());
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {
                      "--platform",
                      "gitlab",
                      "--gitlab-project",
                      "g/p",
                      "--pr",
                      "1",
                      "--base",
                      "b",
                      "--head",
                      "h"
                    },
                    env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN_FILE");
  }

  @Test
  void gitlabModeRejectsCommandLineToken() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {
                      "--platform",
                      "gitlab",
                      "--gitlab-project",
                      "g/p",
                      "--pr",
                      "1",
                      "--base",
                      "b",
                      "--head",
                      "h",
                      "--token",
                      "leaked"
                    },
                    env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN");
  }

  @Test
  void gitlabModeRequiresToken() {
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {
                      "--platform",
                      "gitlab",
                      "--gitlab-project",
                      "g/p",
                      "--pr",
                      "1",
                      "--base",
                      "b",
                      "--head",
                      "h"
                    },
                    baseEnv()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN");
  }

  @Test
  void invalidPlatformFailsFast() {
    assertThatThrownBy(() -> CliArgs.parse(new String[] {"--platform", "gerrit"}, baseEnv()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("github|gitlab");
  }

  @Test
  void localModeIsPlatformNeutralAndNeedsNoGitlabToken() {
    Map<String, String> env = baseEnv();
    env.remove("CRA_LLM_API_KEY");
    CraConfig config =
        CliArgs.parse(
            new String[] {"--local", "--platform", "gitlab", "--gitlab-project", "g/p"}, env);
    assertThat(config.local()).isTrue();
    assertThat(config.isGitlab()).isTrue();
    assertThat(config.gitlab().token()).isNull();
  }

  @Test
  void githubModeDoesNotPickUpGitlabEnvironment() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN", "glpat-should-not-leak");
    env.put("CI_PROJECT_PATH", "gitlab/only");
    CraConfig config =
        CliArgs.parse(
            new String[] {"--repo", "o/r", "--pr", "7", "--base", "b", "--head", "h"}, env);
    assertThat(config.platform()).isEqualTo("github");
    assertThat(config.repoOwner()).isEqualTo("o");
    assertThat(config.githubToken()).isEqualTo("gh-token");
    assertThat(config.gitlab().token()).isNull();
  }

  @Test
  void normalizeApiBaseAcceptsBothForms() {
    assertThat(PlatformArgs.normalizeApiBase("https://gitlab.com"))
        .isEqualTo("https://gitlab.com/api/v4");
    assertThat(PlatformArgs.normalizeApiBase("https://gitlab.com/api/v4/"))
        .isEqualTo("https://gitlab.com/api/v4");
    assertThat(PlatformArgs.normalizeApiBase("https://host/prefix/api/v4/x"))
        .isEqualTo("https://host/prefix/api/v4");
  }

  @Test
  void splitRepoAllowsNestedGitlabGroupOnly() {
    PlatformArgs.OwnerRepo nested = PlatformArgs.splitRepo("group/sub/proj", true);
    assertThat(nested.owner()).isEqualTo("group/sub");
    assertThat(nested.name()).isEqualTo("proj");
    assertThatThrownBy(() -> PlatformArgs.splitRepo("group/sub/proj", false))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> PlatformArgs.splitRepo("broken", true))
        .isInstanceOf(CraConfigException.class);
  }

  @Test
  void sweepParsesGitlabPlatform() {
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN", "glpat-sweep");
    SweepArgs.Parsed parsed =
        SweepArgs.parse(
            new String[] {"--platform", "gitlab", "--gitlab-project", "group/sub/proj"}, env);
    assertThat(parsed.config().platform()).isEqualTo("gitlab");
    assertThat(parsed.config().repoOwner()).isEqualTo("group/sub");
    assertThat(parsed.config().repoName()).isEqualTo("proj");
    assertThat(parsed.config().gitlab().token()).isEqualTo("glpat-sweep");
    assertThat(parsed.config().githubToken()).isNull();
  }

  @Test
  void sweepBlankFallbackChainEnvKeepsDefault() {
    // GitHub Actions 空 env（""）等同未设置：不得静默丢失降级链（与 CliArgs envOr 同语义）
    Map<String, String> env = baseEnv();
    env.put("CRA_LLM_FALLBACK_CHAIN", "");
    SweepArgs.Parsed parsed = SweepArgs.parse(new String[] {"--repo", "o/r"}, env);
    assertThat(parsed.config().fallbackChain()).isEqualTo("standard,light");
    assertThat(parsed.config().runTotalTokens())
        .isEqualTo(org.cra.core.api.PipelineOptions.DEFAULT_RUN_TOTAL_TOKENS);
    assertThat(parsed.config().nitBudget()).isEqualTo(3);
    assertThat(parsed.config().spotCheckPercent()).isEqualTo(20);
    assertThat(parsed.config().degradeAtFirst()).isEqualTo(0.70);
    assertThat(parsed.config().degradeAtSecond()).isEqualTo(0.85);
  }

  @Test
  void sharedTokenGuardRejectsGitlabCommandLineToken() {
    // R5 共享函数：GitLab 拒收 --token 的守卫收敛到 PlatformArgs.tokenOrGitlabGuard（review/sweep 同源）
    Map<String, String> env = baseEnv();
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    assertThatThrownBy(
            () -> PlatformArgs.tokenOrGitlabGuard(Map.of("--token", "leaked"), env, true))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN")
        .hasMessageContaining("--token 仅 GitHub");
    // github 形态：--token 优先于 GITHUB_TOKEN；GitLab 形态：只认 env/文件
    assertThat(PlatformArgs.tokenOrGitlabGuard(Map.of("--token", "gh-arg"), env, false))
        .isEqualTo("gh-arg");
    assertThat(PlatformArgs.tokenOrGitlabGuard(Map.of(), env, false)).isEqualTo("gh-token");
    assertThat(PlatformArgs.tokenOrGitlabGuard(Map.of(), env, true)).isEqualTo("glpat-env");
  }

  @Test
  void sharedRepoFallbackMatchesCommandLineSemantics() {
    // R5 共享函数：GitLab 项目回退链与 GitHub repo 回退（review/sweep 同源）
    Map<String, String> env = new HashMap<>(baseEnv());
    env.put("CI_PROJECT_PATH", "ci/sub/proj");
    assertThat(PlatformArgs.repoOrProject(Map.of("--repo", "o/r"), env, false)).isEqualTo("o/r");
    assertThat(PlatformArgs.repoOrProject(Map.of(), env, false))
        .isEqualTo(env.get("GITHUB_REPOSITORY"));
    assertThat(PlatformArgs.repoOrProject(Map.of(), env, true)).isEqualTo("ci/sub/proj");
    assertThat(PlatformArgs.repoOrProject(Map.of("--repo", "o/r"), env, true))
        .isEqualTo("ci/sub/proj"); // GitLab：CI_PROJECT_PATH 优先于 --repo
    assertThat(PlatformArgs.repoOrProject(Map.of(), Map.of(), true)).isNull();
    // 必填形态缺失即拒（sweep/CI 共用同一文案）
    assertThatThrownBy(() -> PlatformArgs.requireOwnerRepo(Map.of(), Map.of(), false))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--repo（或 GITHUB_REPOSITORY）");
    assertThatThrownBy(() -> PlatformArgs.requireOwnerRepo(Map.of(), Map.of(), true))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--gitlab-project / CRA_GITLAB_PROJECT / CI_PROJECT_PATH");
  }

  @Test
  void sharedLlmEnvTripleAndDepthAndModes() {
    // R5 共享函数：LLM 三键 / depth / crosschecker-modes 的组装期校验
    Map<String, String> llmEnv =
        Map.of(
            "CRA_LLM_API_KEY",
            "sk",
            "CRA_LLM_BASE_URL",
            "https://llm.example/v1",
            "CRA_LLM_MODEL",
            "glm-x");
    PlatformArgs.LlmEnvTriple triple = PlatformArgs.llmEnvTriple(llmEnv, true);
    assertThat(triple.apiKey()).isEqualTo("sk");
    assertThat(PlatformArgs.llmEnvTriple(Map.of(), false).apiKey()).isNull(); // 本地形态可缺省
    assertThatThrownBy(() -> PlatformArgs.llmEnvTriple(Map.of("CRA_LLM_API_KEY", "sk"), true))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_BASE_URL");

    assertThat(PlatformArgs.depthCheck("quick")).isEqualTo("quick");
    assertThatThrownBy(() -> PlatformArgs.depthCheck("ultra"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("depth 非法");

    assertThat(PlatformArgs.crosscheckerModes(Map.of(), Map.of())).isEqualTo("assumption,sniff");
    assertThatThrownBy(
            () -> PlatformArgs.crosscheckerModes(Map.of("--crosschecker-modes", "bad"), Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--crosschecker-modes 非法");
    assertThatThrownBy(
            () -> PlatformArgs.crosscheckerModes(Map.of(), Map.of("CRA_CROSSCHECKER_MODES", ",")))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--crosschecker-modes 不能为空");
  }

  @Test
  void sharedTriStateMatchingOnlyAcceptsDeclaredForms() {
    // `--flag=value` 形式仅 tri-state 旗标合法；其余形态返回 null（未知参数掩码由调用方 default 分支保留）
    assertThat(PlatformArgs.matchTriState("--crosschecker", "--crosschecker")).isNotNull();
    assertThat(PlatformArgs.matchTriState("--crosschecker=false", "--crosschecker"))
        .isEqualTo(new PlatformArgs.TriState("--crosschecker", false));
    assertThat(PlatformArgs.matchTriState("--crosschecker=TRUE", "--crosschecker")).isNull();
    assertThat(PlatformArgs.matchTriState("--repo=o/r", "--repo")).isNull();
  }

  private static Map<String, String> withToken(Map<String, String> env) {
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    return env;
  }
}
