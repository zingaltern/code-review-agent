package org.cra.common.context;

/** token 估算（04§2.1：字符数 / 3.5 近似）。cra-context 与 cra-llm 各自持有同公式实现（矩阵禁 context→llm 依赖）。 */
public final class TokenMath {

  private TokenMath() {}

  /**
   * 估算文本 token 数。
   *
   * @param text 任意文本（null 视为空）
   * @return 估算 token（向上取整；空文本为 0）
   */
  public static long estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return (long) Math.ceil(text.length() / 3.5);
  }
}
