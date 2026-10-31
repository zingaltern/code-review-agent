package org.cra.git.github.internal;

import org.cra.common.exception.CraIntegrationException;

/**
 * GitHub REST 状态码异常（边界转换用）：状态码与路径作为结构化字段携带，降级判定不再解析 message 文案。
 *
 * <p>message 保持 "GitHub API {status} on {path}: {body 摘要}" 形态，便于日志与既有断言阅读。
 */
final class GitHubApiException extends CraIntegrationException {

  private final int status;
  private final String path;

  GitHubApiException(int status, String path, String message) {
    super(message);
    this.status = status;
    this.path = path;
  }

  int status() {
    return status;
  }

  String path() {
    return path;
  }
}
