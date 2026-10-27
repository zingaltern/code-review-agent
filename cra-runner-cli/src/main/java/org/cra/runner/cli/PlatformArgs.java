package org.cra.runner.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/**
 * Git 平台选择与 GitLab 连接参数解析（M4e 第二半；ADR-0012）。
 *
 * <p>平台优先级：`--platform` &gt; `CRA_GIT_PLATFORM` &gt; `github`（默认保持既有行为）。GitLab 的仓库标识、token 与 API
 * 根沿用 GitLab CI 变量；token 只认环境变量/文件，命令行 `--token` 仅属于 GitHub（避免跨平台串用密钥）。
 */
final class PlatformArgs {

  static final String GITHUB = "github";
  static final String GITLAB = "gitlab";
  static final String DEFAULT_GITLAB_API_BASE = "https://gitlab.com/api/v4";

  private PlatformArgs() {}

  /**
   * 解析平台选择。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return `github` 或 `gitlab`（小写）
   */
  static String platform(Map<String, String> named, Map<String, String> env) {
    String raw = firstNonBlank(named.get("--platform"), env.get("CRA_GIT_PLATFORM"), GITHUB);
    String platform = raw.strip().toLowerCase(java.util.Locale.ROOT);
    if (!GITHUB.equals(platform) && !GITLAB.equals(platform)) {
      throw new CraConfigException("--platform / CRA_GIT_PLATFORM 非法: " + raw + "（github|gitlab）");
    }
    return platform;
  }

  /**
   * GitLab 连接设置（仅 `platform=gitlab` 时调用）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return 归一后的 GitLab 设置
   */
  static GitLabSettings gitlabSettings(Map<String, String> named, Map<String, String> env) {
    return new GitLabSettings(
        normalizeApiBase(
            firstNonBlank(
                named.get("--gitlab-base-url"),
                env.get("CRA_GITLAB_BASE_URL"),
                DEFAULT_GITLAB_API_BASE)),
        gitlabToken(env),
        blankToNull(env.get("CRA_GITLAB_BOT_USERNAME")));
  }

  /**
   * GitLab 项目路径（`group/project` 或嵌套 `group/subgroup/project`）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return 项目路径；未配置为 null
   */
  static String gitlabProject(Map<String, String> named, Map<String, String> env) {
    return blankToNull(
        firstNonBlank(
            named.get("--gitlab-project"),
            env.get("CRA_GITLAB_PROJECT"),
            env.get("CI_PROJECT_PATH")));
  }

  /**
   * 拆分仓库标识：GitHub 只允许 `owner/name`；GitLab 允许嵌套 group（按最后一个 `/` 切分）。
   *
   * @param full `owner/name` 或 `group/subgroup/project`
   * @param allowNestedGroup GitLab = true
   * @return `[owner, name]`
   */
  static String[] splitRepo(String full, boolean allowNestedGroup) {
    int lastSlash = full.lastIndexOf('/');
    if (lastSlash <= 0 || lastSlash == full.length() - 1) {
      throw new CraConfigException(
          (allowNestedGroup ? "--gitlab-project 形如 group/project: " : "--repo 形如 owner/name: ")
              + full);
    }
    String owner = full.substring(0, lastSlash);
    String name = full.substring(lastSlash + 1);
    if (!allowNestedGroup && owner.contains("/")) {
      throw new CraConfigException("--repo 形如 owner/name: " + full);
    }
    return new String[] {owner, name};
  }

  /**
   * API 根归一：接受实例根或已含 `/api/v4` 的地址。
   *
   * @param raw 用户输入
   * @return 以 `/api/v4` 结尾且无尾随 `/` 的 API 根
   */
  static String normalizeApiBase(String raw) {
    String base = raw.strip().replaceAll("/+$", "");
    if (base.endsWith("/api/v4")) {
      return base;
    }
    int marker = base.indexOf("/api/v4/");
    if (marker >= 0) {
      return base.substring(0, marker + "/api/v4".length());
    }
    return base + "/api/v4";
  }

  /**
   * 密钥读取：`CRA_GITLAB_TOKEN` 优先，其次 `CRA_GITLAB_TOKEN_FILE` 指向的文件（内容去首尾空白）。
   *
   * @param env 环境变量
   * @return token；均未配置为 null
   */
  static String gitlabToken(Map<String, String> env) {
    String token = blankToNull(env.get("CRA_GITLAB_TOKEN"));
    if (token != null) {
      return token;
    }
    String tokenFile = blankToNull(env.get("CRA_GITLAB_TOKEN_FILE"));
    if (tokenFile == null) {
      return null;
    }
    try {
      return blankToNull(Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8));
    } catch (IOException | java.nio.file.InvalidPathException e) {
      throw new CraConfigException("CRA_GITLAB_TOKEN_FILE 读取失败: " + tokenFile);
    }
  }

  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
