package org.cra.git.gitlab.internal;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * GitLab payload 的纯解析/编码辅助：严格 UTF-8、glob→regex、项目与文件路径编码（ADR-0011）。
 *
 * <p>GitLab 要求项目路径与文件完整路径整体 URL 编码（`group%2Fproject`、`src%2Fmain%2FFoo.java`）， 因此不能复用按段拼接的 GitHub
 * 编码。
 */
final class GitLabPayloadSupport {

  private GitLabPayloadSupport() {}

  static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  static Pattern globToRegex(String glob) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          sb.append(".*");
          i++;
        } else {
          sb.append("[^/]*");
        }
      } else if (c == '?') {
        sb.append('.');
      } else {
        sb.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(sb.toString());
  }

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
