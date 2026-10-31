package org.cra.llm.internal;

import java.nio.charset.StandardCharsets;

/**
 * token 估算——UTF-8 字节数 / 3.5（04§2.1 的"字符数/3.5"在中文文本会低估约 3 倍，此处按字节修正；与 cra-common TokenMath
 * 的字符口径差异见该类注）—— usage 缺失时的兜底与失败路径的已消耗计量共用。
 */
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
