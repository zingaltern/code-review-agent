package org.cra.core.internal.pipeline;

import java.util.List;

/**
 * L2 跨文件切片产出（ADR-0014）。
 *
 * @param text 切片文本（brief 的 {@code untrusted_slices} 槽；不可得/未触发为空串）
 * @param notes 覆盖声明（识别结果、截断事实；未触发为空列表）
 * @param symbols 实际注入的符号数
 * @param files 实际注入的关联文件数
 * @param sites 实际注入的引用位置数
 * @param truncated 是否因预算截断
 */
public record CrossFileSlices(
    String text, List<String> notes, int symbols, int files, int sites, boolean truncated) {

  public CrossFileSlices {
    text = text == null ? "" : text;
    notes = List.copyOf(notes == null ? List.of() : notes);
  }

  /**
   * 空产出（未启用 / 不可得 / 无可注入符号）。
   *
   * @return 空切片
   */
  public static CrossFileSlices empty() {
    return new CrossFileSlices("", List.of(), 0, 0, 0, false);
  }

  /**
   * 是否有可注入内容。
   *
   * @return true 若文本非空
   */
  public boolean present() {
    return !text.isBlank();
  }

  /**
   * 切片文本的估算 token（04§2.1 口径；用于从 L1 预算中扣除，避免 brief 超单代理输入预算）。
   *
   * @return 估算 token
   */
  public long estimatedTokens() {
    return org.cra.common.context.TokenMath.estimateTokens(text);
  }
}
