package org.cra.common.git;

import org.cra.common.finding.Side;

/**
 * unified diff 单个 hunk（行号区间，供锚定校验与 L1 窗口切分，02§4.1/05§2.2）。
 *
 * @param oldStart 旧文件起始行（1 基）
 * @param oldLines 旧文件行数
 * @param newStart 新文件起始行（1 基）
 * @param newLines 新文件行数
 */
public record DiffHunk(int oldStart, int oldLines, int newStart, int newLines) {

  public DiffHunk {
    if (oldStart < 0 || oldLines < 0 || newStart < 0 || newLines < 0) {
      throw new IllegalArgumentException(
          "negative hunk range: %d,%d %d,%d".formatted(oldStart, oldLines, newStart, newLines));
    }
  }

  /**
   * 判断行号是否落在本 hunk 的指定侧内（锚定校验用，05§2.2）。
   *
   * @param side 行号侧
   * @param line 1 基行号
   * @return true 若落在本 hunk 内
   */
  public boolean containsLine(Side side, int line) {
    return switch (side) {
      case RIGHT -> line >= newStart && line < newStart + Math.max(newLines, 1);
      case LEFT -> line >= oldStart && line < oldStart + Math.max(oldLines, 1);
    };
  }
}
