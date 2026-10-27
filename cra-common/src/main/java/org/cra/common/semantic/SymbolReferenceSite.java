package org.cra.common.semantic;

import java.util.Objects;

/**
 * 符号引用位置（跨文件切片的最小证据单元；ADR-0014）。
 *
 * @param path 引用所在文件（相对仓库根）
 * @param line 引用行（1 基，head 侧行号）
 * @param snippet 引用行摘录（去首尾空白、长度受限）
 */
public record SymbolReferenceSite(String path, int line, String snippet) {

  public SymbolReferenceSite {
    Objects.requireNonNull(path, "path");
    if (line < 1) {
      throw new IllegalArgumentException("line must be >= 1: " + line);
    }
    snippet = snippet == null ? "" : snippet;
  }
}
