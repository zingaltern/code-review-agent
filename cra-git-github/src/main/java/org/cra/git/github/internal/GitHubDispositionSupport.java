package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.cra.common.git.DispositionSignal;
import org.cra.common.text.Texts;

/**
 * 处置信号解析（06§1.1）：人类 reply 标签与时间字段容错。
 *
 * <p>从 {@link GitHubHttpAdapter} 拆出，避免响应映射与标签语义混在端口实现里。
 */
final class GitHubDispositionSupport {

  private static final Pattern TAG_WRONG = Pattern.compile("(?i)(:wrong:|not-a-bug|\\[wrong\\])");
  private static final Pattern TAG_INTENTIONAL =
      Pattern.compile("(?i)(:intentional:|\\[intentional\\])");
  private static final Pattern TAG_FIXED = Pattern.compile("(?i)(:fixed:|\\[fixed\\])");

  private GitHubDispositionSupport() {}

  static String replyTag(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    if (TAG_WRONG.matcher(body).find()) {
      return DispositionSignal.TAG_WRONG;
    }
    if (TAG_INTENTIONAL.matcher(body).find()) {
      return DispositionSignal.TAG_INTENTIONAL;
    }
    if (TAG_FIXED.matcher(body).find()) {
      return DispositionSignal.TAG_FIXED;
    }
    return null;
  }

  static String excerpt(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    String oneLine = body.replaceAll("\\s+", " ").strip();
    return Texts.truncate(oneLine, 200);
  }

  static Instant instantOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
      return null;
    }
    try {
      return Instant.parse(node.asText());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  static Instant instantOrEpoch(JsonNode node) {
    Instant parsed = instantOrNull(node);
    return parsed == null ? Instant.EPOCH : parsed;
  }
}
