package org.cra.llm.internal;

import java.nio.charset.StandardCharsets;

/** token 估算（04§2.1：字符数 / 3.5 近似）——usage 缺失时的兜底与 brief 组装期的预估共用。 */
final class TokenEstimator {

  private static final double CHARS_PER_TOKEN = 3.5;

  private TokenEstimator() {}

  /**
   * 估算文本 token 数（向上取整）。
   *
   * @param text 文本（null 视为 0）
   * @return 估算 token 数
   */
  static long estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return (long) Math.ceil(text.getBytes(StandardCharsets.UTF_8).length / CHARS_PER_TOKEN);
  }
}
