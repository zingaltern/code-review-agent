package org.cra.core.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.cra.common.git.LedgerFindingMarker;

/**
 * 指纹级去重判定（02§4.1 第二道闸，08§5 场景 8/9 的承重类）：exact → ±5 滑窗 → possible_duplicate 三层。
 *
 * <ul>
 *   <li><b>exact</b>：新锚行处的完整指纹（file|category|锚行哈希|±2 上下文哈希）与账本标记指纹相等；
 *   <li><b>sliding-window</b>：完整指纹未命中时，新代码锚行 ±5 行窗口内逐行取内容哈希，与账本标记的 锚行内容哈希比对，命中判同指纹抑制。完整指纹含上下文哈希且
 *       sha256 不可分解、账本标记只存截断 指纹，故标记侧锚行哈希由调用方按标记的 commit_sha 取旧文件内容重算后注入（02§4.1 M1 实现口径）；
 *   <li><b>possible_duplicate</b>：两闸均未命中，但同文件同类别且标记行与新锚行距离 ≤5（行区间重叠的 单行近似）——只入 trace 供人工复核，不自动抑制。
 * </ul>
 *
 * <p>纯函数：无 IO、无状态，账本快照与旧锚行哈希解析器全部走参数（08§5 场景 8/9 直接单测）。
 */
final class FingerprintDeduper {

  /** 滑窗半径（02§4.1：±5 行）。 */
  static final int SLIDING_RADIUS = 5;

  /** 去重结论。 */
  enum Match {
    /** 完整指纹精确命中 */
    EXACT,
    /** 滑窗锚行内容哈希命中 */
    SLIDING_WINDOW,
    /** 未命中（可能伴随 possible_duplicate 标记） */
    NONE
  }

  /**
   * @param fingerprint 该 finding 的完整指纹 v2（发布标记 fp 字段）
   * @param match 命中层级
   * @param possibleDuplicate 滑窗未命中但同文件同类别行距 ≤5（trace 供人工复核，不抑制）
   * @param matched 命中的账本标记（未命中为 empty）
   */
  record Decision(
      String fingerprint,
      Match match,
      boolean possibleDuplicate,
      Optional<LedgerFindingMarker> matched) {}

  private FingerprintDeduper() {}

  /**
   * 判定一条候选 finding 是否与账本已发布指纹重复。
   *
   * @param file finding 文件路径
   * @param category 类别（小写，与账本标记形态一致）
   * @param line finding 锚行（新代码侧绝对行号）
   * @param newLines 新代码侧文件内容（按 \n 切分）
   * @param publishedMarkers 账本已发布 finding 标记（全部 PR 标记，内部按 file+category 过滤）
   * @param oldAnchorLineHash 标记 → 发布时锚行内容哈希（调用方按 commit_sha 重算；不可得为 empty， 该标记不参与滑窗）
   * @return 去重结论
   */
  static Decision evaluate(
      String file,
      String category,
      int line,
      List<String> newLines,
      List<LedgerFindingMarker> publishedMarkers,
      Function<LedgerFindingMarker, Optional<String>> oldAnchorLineHash) {
    List<LedgerFindingMarker> sameSpot =
        publishedMarkers.stream()
            .filter(m -> m.file().equals(file))
            .filter(m -> m.category().equalsIgnoreCase(category))
            .toList();
    String fingerprint =
        FingerprintCalculator.fingerprint(
            file,
            category.toLowerCase(Locale.ROOT),
            lineAt(newLines, line),
            contextWindow(newLines, line));
    Optional<LedgerFindingMarker> exact =
        sameSpot.stream().filter(m -> m.fingerprint().equals(fingerprint)).findFirst();
    if (exact.isPresent()) {
      return new Decision(fingerprint, Match.EXACT, false, exact);
    }
    for (LedgerFindingMarker marker : sameSpot) {
      Optional<String> oldHash = oldAnchorLineHash.apply(marker);
      if (oldHash.isEmpty()) {
        continue;
      }
      int from = Math.max(1, line - SLIDING_RADIUS);
      int to = Math.min(newLines.size(), line + SLIDING_RADIUS);
      for (int i = from; i <= to; i++) {
        if (FingerprintCalculator.sha256(lineAt(newLines, i).strip()).equals(oldHash.get())) {
          return new Decision(fingerprint, Match.SLIDING_WINDOW, false, Optional.of(marker));
        }
      }
    }
    boolean possible = sameSpot.stream().anyMatch(m -> Math.abs(m.line() - line) <= SLIDING_RADIUS);
    return new Decision(fingerprint, Match.NONE, possible, Optional.empty());
  }

  /**
   * 锚行内容（与发布侧 fingerprintOf 同语义）：行号越界为空串，保留其余字符由指纹计算去首尾空白。
   *
   * @param lines 文件行（0 基下标）
   * @param line 1 基行号
   * @return 该行原文；越界为空串
   */
  static String lineAt(List<String> lines, int line) {
    return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
  }

  /**
   * 锚行 ±2 行上下文窗口（与发布侧 fingerprintOf 同语义，行不足时截取可用部分）。
   *
   * @param lines 文件行（0 基下标）
   * @param line 1 基锚行号
   * @return 上下文行列表（含锚行）
   */
  static List<String> contextWindow(List<String> lines, int line) {
    int from = Math.min(Math.max(0, line - 3), lines.size());
    int to = Math.min(Math.max(from, line + 2), lines.size());
    return lines.subList(from, to);
  }
}
