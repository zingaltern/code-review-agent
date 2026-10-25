package org.cra.common.git;

import java.util.List;
import java.util.Objects;

/**
 * 变更集内单个文件（compare API files[] 条目，02§2 步骤 2）。
 *
 * @param path 新路径（删除文件为旧路径）
 * @param previousPath 重命名前路径，非重命名为 null
 * @param status GitHub 文件变更状态：added/modified/removed/renamed/changed
 * @param patch unified diff 全文（二进制文件为 null）
 * @param hunks 从 patch 解析出的 hunk 区间列表（patch 为 null 时为空）
 */
public record DiffFile(
    String path, String previousPath, String status, String patch, List<DiffHunk> hunks) {

  public DiffFile {
    Objects.requireNonNull(path, "path");
    if (path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    status = status == null ? "modified" : status;
    hunks = List.copyOf(hunks == null ? List.of() : hunks);
  }

  /**
   * 判断 (side, line) 是否落在本文件任一 hunk 内（锚定校验，05§2.2）。
   *
   * @param side 行号侧
   * @param line 1 基行号
   * @return true 若任一 hunk 命中
   */
  public boolean containsLine(org.cra.common.finding.Side side, int line) {
    return hunks.stream().anyMatch(h -> h.containsLine(side, line));
  }
}
