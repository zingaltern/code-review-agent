package org.cra.common.semantic;

import java.util.Objects;

/**
 * 符号定义（语义索引快照内容之一；ADR-0014）。
 *
 * @param path 定义所在文件（相对仓库根）
 * @param name 符号名（类型名 / 方法名 / 字段名；不含签名）
 * @param kind 符号种类
 * @param line 定义行（1 基，head 侧行号）
 * @param owner 所属类型名（TYPE 为 null；方法/字段为其直接外层类型）
 * @param snippet 定义行摘录（去首尾空白、长度受限；用于切片内联证据，避免二次取数）
 */
public record SymbolDefinition(
    String path, String name, SymbolKind kind, int line, String owner, String snippet) {

  public SymbolDefinition {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    if (line < 1) {
      throw new IllegalArgumentException("line must be >= 1: " + line);
    }
    snippet = snippet == null ? "" : snippet;
  }
}
