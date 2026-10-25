package org.cra.common.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private static final Pattern HUNK_HEADER =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  public DiffHunk {
    if (oldStart < 0 || oldLines < 0 || newStart < 0 || newLines < 0) {
      throw new IllegalArgumentException(
          "negative hunk range: %d,%d %d,%d".formatted(oldStart, oldLines, newStart, newLines));
    }
  }

  /**
   * 从 unified diff patch 全文解析 hunk 区间（平台无关：GitHub compare 与本地 jgit DiffFormatter 产出的 patch 同构）。行首非
   * hunk 头的行一律忽略。
   *
   * @param patch patch 全文（null 视为空）
   * @return hunk 区间列表（按出现序）
   */
  public static List<DiffHunk> parseAll(String patch) {
    List<DiffHunk> hunks = new ArrayList<>();
    if (patch == null) {
      return hunks;
    }
    for (String line : patch.split("\n", -1)) {
      Matcher m = HUNK_HEADER.matcher(line);
      if (m.matches()) {
        hunks.add(
            new DiffHunk(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4) == null ? 1 : Integer.parseInt(m.group(4))));
      }
    }
    return hunks;
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
