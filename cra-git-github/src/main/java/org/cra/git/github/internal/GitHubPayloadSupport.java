package org.cra.git.github.internal;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.git.DiffHunk;

/**
 * GitHub payload 的纯解析/编码辅助：diff hunk、严格 UTF-8、glob→regex、路径与 URL 编码。
 *
 * <p>从 {@link GitHubHttpAdapter} 拆出，均为无状态纯函数，便于单独测试与复用。
 */
final class GitHubPayloadSupport {

  private static final Pattern HUNK_HEADER =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  private GitHubPayloadSupport() {}

  static List<DiffHunk> parseHunks(String patch) {
    List<DiffHunk> hunks = new ArrayList<>();
    for (String line : patch.split("\n", -1)) {
      Matcher m = HUNK_HEADER.matcher(line);
      if (m.matches()) {
        hunks.add(
            new DiffHunk(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4) == null ? 1 : Integer.parseInt(m.group(4))));
      }
    }
    return hunks;
  }

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

  static String encodePath(String path) {
    return String.join(
        "/", Arrays.stream(path.split("/")).map(GitHubPayloadSupport::urlEncode).toList());
  }

  static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
