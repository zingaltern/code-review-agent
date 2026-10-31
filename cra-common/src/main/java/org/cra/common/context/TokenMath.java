package org.cra.common.context;

/**
 * token 估算（04§2.1：字符数 / 3.5 近似）。cra-context 与 cra-llm 各自持有一份（矩阵禁 context→llm 依赖），但公式并不相同——
 * 已知口径分歧（勿当"同公式"引用）：本类按 {@code text.length()}（字符数）；cra-llm TokenEstimator 按 UTF-8 字节数——中文文本下
 * 后者约为前者的 3 倍（前者用于 brief 预算门槛、后者用于 usage 缺失计量，统一属跨模块行为变更，需走 ADR）。
 */
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
