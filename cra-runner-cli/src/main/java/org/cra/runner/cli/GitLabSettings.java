package org.cra.runner.cli;

/**
 * GitLab 连接设置（M4e 第二半；ADR-0012）。
 *
 * <p>密钥只从环境变量或密钥文件读取（05§6）：`CRA_GITLAB_TOKEN` / `CRA_GITLAB_TOKEN_FILE`；不接受命令行明文 token。
 *
 * @param baseUrl API 根（含 /api/v4；由 {@link PlatformArgs#normalizeApiBase} 归一）
 * @param token GitLab access token（PRIVATE-TOKEN）
 * @param botUsername `/user` 不可读时的 bot 身份回退（CI job token 场景，ADR-0011 决策 3）
 */
record GitLabSettings(String baseUrl, String token, String botUsername) {

  /** GitHub 形态的占位值：不使用 GitLab 时保持空，避免把无关密钥带进配置对象。 */
  static GitLabSettings none() {
    return new GitLabSettings("", null, null);
  }
}
