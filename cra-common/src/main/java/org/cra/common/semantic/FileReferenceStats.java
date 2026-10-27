package org.cra.common.semantic;

import java.util.Objects;

/**
 * 文件级引用统计（语义索引快照内容之一；ADR-0014）。
 *
 * <p>三个计数的口径：{@code definitions} = 本文件内的定义数；{@code outboundReferences} = 本文件内出现的 "索引内已定义符号名"
 * 次数；{@code inboundReferences} = 其他文件引用 "本文件内定义的符号名" 的次数。 计数不受位置列表上限影响（精确值）。
 *
 * @param path 文件路径（相对仓库根）
 * @param definitions 本文件定义数
 * @param outboundReferences 本文件向外的符号引用次数
 * @param inboundReferences 其他文件对本文件符号的引用次数
 */
public record FileReferenceStats(
    String path, int definitions, int outboundReferences, int inboundReferences) {

  public FileReferenceStats {
    Objects.requireNonNull(path, "path");
    if (definitions < 0 || outboundReferences < 0 || inboundReferences < 0) {
      throw new IllegalArgumentException(
          "reference counts must be >= 0: %d/%d/%d"
              .formatted(definitions, outboundReferences, inboundReferences));
    }
  }
}
