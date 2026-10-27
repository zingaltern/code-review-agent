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

/** M4e 第二半：CLI 平台选择与 GitLab 配置解析（ADR-0012；默认 github 保持兼容）。 */
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
    assertThat(PlatformArgs.splitRepo("group/sub/proj", true)).containsExactly("group/sub", "proj");
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
  }

  private static Map<String, String> withToken(Map<String, String> env) {
    env.put("CRA_GITLAB_TOKEN", "glpat-env");
    return env;
  }
}
