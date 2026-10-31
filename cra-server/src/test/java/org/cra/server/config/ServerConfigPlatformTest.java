package org.cra.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** cra-server 平台选择配置（M4e 第二半，ADR-0013）。 */
class ServerConfigPlatformTest {

  @TempDir Path tempDir;

  @Test
  void defaultsToGithub() {
    ServerConfig config = ServerConfig.from(baseEnv());
    assertThat(config.platform()).isEqualTo("github");
    assertThat(config.isGitlab()).isFalse();
    assertThat(config.gitlabToken()).isNull();
  }

  @Test
  void gitlabRequiresTokenAndNormalizesBaseUrl() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "gitlab");
    assertThatThrownBy(() -> ServerConfig.from(env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN");

    env.setProperty("CRA_GITLAB_TOKEN", "glpat-server");
    env.setProperty("CRA_GITLAB_BASE_URL", "https://gitlab.example.com/");
    env.setProperty("CRA_GITLAB_BOT_USERNAME", "cra-bot");
    ServerConfig config = ServerConfig.from(env);
    assertThat(config.isGitlab()).isTrue();
    assertThat(config.gitlabToken()).isEqualTo("glpat-server");
    assertThat(config.gitlabBaseUrl()).isEqualTo("https://gitlab.example.com/api/v4");
    assertThat(config.gitlabBotUsername()).isEqualTo("cra-bot");
  }

  @Test
  void gitlabTokenCanComeFromFile() throws Exception {
    Path tokenFile = tempDir.resolve("token");
    Files.writeString(tokenFile, " glpat-file\n");
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "gitlab");
    env.setProperty("CRA_GITLAB_TOKEN_FILE", tokenFile.toString());
    assertThat(ServerConfig.from(env).gitlabToken()).isEqualTo("glpat-file");
  }

  @Test
  void missingTokenFileFailsAsConfig() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "gitlab");
    env.setProperty("CRA_GITLAB_TOKEN_FILE", tempDir.resolve("missing").toString());
    assertThatThrownBy(() -> ServerConfig.from(env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_GITLAB_TOKEN_FILE");
  }

  @Test
  void invalidOrUppercasePlatformFailsFast() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "gerrit");
    assertThatThrownBy(() -> ServerConfig.from(env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("github|gitlab");

    MockEnvironment uppercase = baseEnv();
    uppercase.setProperty("CRA_SERVER_PLATFORM", "GitLab");
    uppercase.setProperty("CRA_GITLAB_TOKEN", "t");
    assertThatThrownBy(() -> ServerConfig.from(uppercase))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("github|gitlab");
  }

  @Test
  void normalizeApiBaseAcceptsInstanceRootAndApiRoot() {
    assertThat(ServerConfig.normalizeGitLabApiBase("https://gitlab.com"))
        .isEqualTo("https://gitlab.com/api/v4");
    assertThat(ServerConfig.normalizeGitLabApiBase("https://gitlab.com/api/v4/"))
        .isEqualTo("https://gitlab.com/api/v4");
    assertThat(ServerConfig.normalizeGitLabApiBase("https://host/api/v4/projects"))
        .isEqualTo("https://host/api/v4");
  }

  @Test
  void rpmInvalidEntriesKeepServerKeyNamesInMessages() {
    // R4 文案参数化：server 侧错误前缀保留 "CRA_LLM_RPM" key 名（CLI 侧对应 --rpm，见 LlmConfigFactoryTest）
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_LLM_RPM", "standard=60,mega=10");
    ServerConfig config = ServerConfig.from(env);
    assertThatThrownBy(() -> config.parsedRpm(org.cra.llm.api.LlmGatewayConfig.defaultRpm()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_RPM 条目非法")
        .hasMessageContaining("mega=10");

    MockEnvironment malformed = baseEnv();
    malformed.setProperty("CRA_LLM_RPM", "standard");
    assertThatThrownBy(
            () ->
                ServerConfig.from(malformed)
                    .parsedRpm(org.cra.llm.api.LlmGatewayConfig.defaultRpm()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_RPM 条目形如 standard=60: standard");
  }

  @Test
  void temperatureAndFallbackChainParseViaSharedFactory() {
    // R4：温度/降级链解析收敛到 LlmParsing，server 侧 key 名前缀保留
    MockEnvironment outOfRange = baseEnv();
    outOfRange.setProperty("CRA_LLM_TEMPERATURE", "2.5");
    assertThatThrownBy(() -> ServerConfig.from(outOfRange).parsedTemperature())
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_TEMPERATURE 非法（0.0–2.0）: 2.5");
    assertThat(ServerConfig.from(baseEnv()).parsedTemperature()).isNull();

    MockEnvironment badChain = baseEnv();
    badChain.setProperty("CRA_LLM_FALLBACK_CHAIN", "standard,ultra");
    assertThatThrownBy(() -> ServerConfig.from(badChain).parsedFallbackChain())
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("CRA_LLM_FALLBACK_CHAIN 档位非法: ultra");
  }

  @Test
  void githubModeDoesNotReadGitlabTokenFile() {
    // ADR-0013 决策 1/3：未选中的平台不读取 token；token 文件不可读不得影响 GitHub 模式启动
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_GITLAB_TOKEN_FILE", tempDir.resolve("missing").toString());
    ServerConfig config = ServerConfig.from(env);
    assertThat(config.platform()).isEqualTo("github");
    assertThat(config.gitlabToken()).isNull();
  }

  @Test
  void gitlabTokenWinsOverUnreadableTokenFile() {
    // CRA_GITLAB_TOKEN 优先，其次才读文件（Java 实参惰性求值：不能因为文件不可读而失败）
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "gitlab");
    env.setProperty("CRA_GITLAB_TOKEN", "glpat-env");
    env.setProperty("CRA_GITLAB_TOKEN_FILE", tempDir.resolve("missing").toString());
    assertThat(ServerConfig.from(env).gitlabToken()).isEqualTo("glpat-env");
  }

  @Test
  void blankPlatformDefaultsToGithub() {
    MockEnvironment env = baseEnv();
    env.setProperty("CRA_SERVER_PLATFORM", "");
    assertThat(ServerConfig.from(env).platform()).isEqualTo("github");
  }

  private static MockEnvironment baseEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("CRA_SERVER_WEBHOOK_SECRET", "wh-secret");
    env.setProperty("CRA_SERVER_API_TOKEN", "api-token");
    env.setProperty("CRA_SERVER_SIGNING_KEY", "signing-key");
    env.setProperty("CRA_SERVER_SIGNING_KEY_ID", "key-v1");
    env.setProperty("CRA_LLM_API_KEY", "sk");
    env.setProperty("CRA_LLM_BASE_URL", "http://127.0.0.1:1/v1");
    env.setProperty("CRA_LLM_MODEL", "m");
    return env;
  }
}
