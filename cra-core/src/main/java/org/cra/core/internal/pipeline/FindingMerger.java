package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.Severity;
import org.cra.core.internal.TraceWriter;

/**
 * 跨代理精确去重合并（T2.2 汇聚侧实现，03§4.5 groupByIdentity/mergeDuplicates 的 M2 前置形态）： 同 file 同 category 且锚行 ±3
 * 重叠的候选合并为一条——保留 severity 高者（important &gt; nit），平局保留先到 （代理派发序，确定性可重放）。语义去重（description 嵌入相似度）留
 * M3（T2.8 语义部分）。
 */
final class FindingMerger {

  /** 同位置判定窗口（03§4.5：同 file ±3 行同 category）。 */
  static final int MERGE_LINE_WINDOW = 3;

  private FindingMerger() {}

  /**
   * 精确去重合并。
   *
   * @param findings 全部代理的候选（派发序）
   * @param trace trace 写入器（被丢弃候选记 dedup/in-run-exact，05§1.4）
   * @return 合并后的候选列表（保序）
   */
  static List<CandidateFinding> mergePrecise(List<CandidateFinding> findings, TraceWriter trace) {
    List<CandidateFinding> kept = new ArrayList<>();
    for (CandidateFinding candidate : findings) {
      int duplicateIndex = -1;
      for (int i = 0; i < kept.size(); i++) {
        if (samePosition(kept.get(i), candidate)) {
          duplicateIndex = i;
          break;
        }
      }
      if (duplicateIndex < 0) {
        kept.add(candidate);
        continue;
      }
      CandidateFinding existing = kept.get(duplicateIndex);
      CandidateFinding loser;
      if (rank(candidate) > rank(existing)) {
        kept.set(duplicateIndex, candidate);
        loser = existing;
      } else {
        loser = candidate;
      }
      trace.emit(
          "dedup",
          Map.of(
              "finding_id", loser.id(),
              "match", "in-run-exact",
              "kept", (rank(candidate) > rank(existing) ? candidate : existing).id()));
    }
    return List.copyOf(kept);
  }

  private static boolean samePosition(CandidateFinding a, CandidateFinding b) {
    return a.file().equals(b.file())
        && a.category() == b.category()
        && Math.abs(a.line() - b.line()) <= MERGE_LINE_WINDOW;
  }

  private static int rank(CandidateFinding finding) {
    return finding.severityProposed() == Severity.IMPORTANT ? 1 : 0;
  }
}
