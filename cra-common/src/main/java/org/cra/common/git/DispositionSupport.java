package org.cra.common.git;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.cra.common.text.Texts;

/**
 * 处置信号解析（06§1.1）：人类 reply 标签与时间字段容错。
 *
 * <p>标签词汇平台无关（{:wrong:|not-a-bug|[wrong]} 等三态，05§2.2 处置语义），GitHub reply 与 GitLab note 共用同一套——
 * GitLab 的回复关系由 discussion 线程表达（无 {@code in_reply_to_id}），标签语义原样复用（ADR-0011 决策 10）。 2026-10-31 由
 * github/gitlab 两份逐字节相同的 DispositionSupport 收敛下沉（ADR-0016）。
 *
 * <p>降级语义（显式声明）：null/blank body 返回 {@code null} 标签与摘录；时间字段缺失/非法返回 {@code null} 或 {@link
 * Instant#EPOCH}（调用方以 EPOCH 兜底排序，06§1.1 口径），均不抛异常。
 */
public final class DispositionSupport {

  private static final Pattern TAG_WRONG = Pattern.compile("(?i)(:wrong:|not-a-bug|\\[wrong\\])");
  private static final Pattern TAG_INTENTIONAL =
      Pattern.compile("(?i)(:intentional:|\\[intentional\\])");
  private static final Pattern TAG_FIXED = Pattern.compile("(?i)(:fixed:|\\[fixed\\])");

  private DispositionSupport() {}

  /**
   * 识别 reply 处置标签（06§1.1：wrong / intentional / fixed）。
   *
   * @param body 评论原文（可空）
   * @return {@link DispositionSignal} 的 TAG_* 常量；null/blank 或无标签时返回 null
   */
  public static String replyTag(String body) {
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

  /**
   * 处置摘录（05§2.2 周报口径）：折叠全部空白为单空格并截断。
   *
   * @param body 评论原文（可空）
   * @return 单行摘录（≤200 字符，超长以 … 结尾）；null/blank 返回 null
   */
  public static String excerpt(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    String oneLine = body.replaceAll("\\s+", " ").strip();
    return Texts.truncate(oneLine, 200);
  }

  /**
   * 解析平台时间字段（容错）。
   *
   * @param node JSON 节点（可 missing/null）
   * @return {@link Instant}；节点缺失/空串/非法 ISO-8601 时返回 null
   */
  public static Instant instantOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
      return null;
    }
    try {
      return Instant.parse(node.asText());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * 解析平台时间字段，不可得时回退 epoch。
   *
   * @param node JSON 节点（可 missing/null）
   * @return {@link Instant}；不可得时返回 {@link Instant#EPOCH}（排序兜底，不抛异常）
   */
  public static Instant instantOrEpoch(JsonNode node) {
    Instant parsed = instantOrNull(node);
    return parsed == null ? Instant.EPOCH : parsed;
  }
}
