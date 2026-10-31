package org.cra.git.github.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * GitHub payload 的编码辅助：路径与 URL 编码。
 *
 * <p>从 {@link GitHubHttpAdapter} 拆出，均为无状态纯函数。此前的 hunk 解析、严格 UTF-8 与 glob→regex 已收敛至 cra-common
 * 共享面（{@code DiffHunk.parseAll} / {@code Texts.strictUtf8} / {@code PathGlobs}，ADR-0016）， 本类只保留
 * GitHub 特有的按段拼接编码。
 */
final class GitHubPayloadSupport {

  private GitHubPayloadSupport() {}

  static String encodePath(String path) {
    return String.join(
        "/", Arrays.stream(path.split("/")).map(GitHubPayloadSupport::encodePathSegment).toList());
  }

  /**
   * 路径段编码：URLEncoder 面向表单（空格 → {@code +}），但路径里的 {@code +} 是字面字符； 空格必须编成 {@code %20}（GitLab 侧同为该语义）。
   */
  static String encodePathSegment(String segment) {
    return urlEncode(segment).replace("+", "%20");
  }

  static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
