package org.cra.git.gitlab.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GitLab payload 的编码辅助：项目与文件路径编码（ADR-0011）。
 *
 * <p>GitLab 要求项目路径与文件完整路径整体 URL 编码（`group%2Fproject`、`src%2Fmain%2FFoo.java`）， 因此不能复用按段拼接的 GitHub
 * 编码。此前的严格 UTF-8 与 glob→regex 已收敛至 cra-common 共享面 （{@code Texts.strictUtf8} / {@code
 * PathGlobs}，ADR-0016）。
 */
final class GitLabPayloadSupport {

  private GitLabPayloadSupport() {}

  /** 编码完整文件路径（斜杠一并编码为 %2F，GitLab File API 要求）。 */
  static String encodeFilePath(String path) {
    return urlEncode(path);
  }

  /** 编码项目路径 `owner/repo` 为 `owner%2Frepo`。 */
  static String encodeProjectPath(String owner, String repo) {
    return urlEncode(owner + "/" + repo);
  }

  static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
