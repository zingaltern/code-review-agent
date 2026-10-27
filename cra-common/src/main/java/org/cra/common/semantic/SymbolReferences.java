package org.cra.common.semantic;

import java.util.List;
import java.util.Objects;

/**
 * 单个符号名的全仓引用统计（语义索引快照内容之一；ADR-0014）。
 *
 * <p>只统计"索引内已定义符号名"的出现次数——未定义的标识符（局部变量、外部库 API）不入索引， 因此这里不承担通用 grep 的语义。位置列表按 (path, line)
 * 升序且受上限约束（每符号 ≤25 处、≤10 文件）， 超限只影响证据条数，不影响 {@code occurrences} 计数。
 *
 * @param name 符号名
 * @param occurrences 全仓出现次数（含定义行自身；精确计数，不受位置上限影响）
 * @param sites 引用位置（排序、有上限；超限时为前 N 处）
 */
public record SymbolReferences(String name, int occurrences, List<SymbolReferenceSite> sites) {

  public SymbolReferences {
    Objects.requireNonNull(name, "name");
    if (occurrences < 0) {
      throw new IllegalArgumentException("occurrences must be >= 0: " + occurrences);
    }
    sites = List.copyOf(sites == null ? List.of() : sites);
  }
}
