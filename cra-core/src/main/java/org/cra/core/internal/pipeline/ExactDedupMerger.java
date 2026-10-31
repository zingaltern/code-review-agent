package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;

/**
 * run 内精确去重合并（07 T2.8；03§4.5 groupByIdentity + mergeDuplicates 的 M2 形态）：同 file ±3 行同 category
 * 的候选合并为一条，代表元按 severity（important 优先）→ confidence（空最低）→ 行号 → id 确定性 选取；语义去重留 M3。跨 run 去重不在本类（账本指纹闸
 * {@link org.cra.core.internal.FingerprintDeduper}）。
 *
 * <p>纯函数：无 IO、无状态（08§5 场景测试直接单测）。
 */
public final class ExactDedupMerger {

  /** 合并行距半径（03§4.5：同 file ±3 行同 category）。 */
  static final int MERGE_RADIUS = 3;

  /**
   * 合并结果。防御拷贝并保序：mergedFrom 的键序 = kept 顺序，ReviewPipeline 按序 emit trace "merge" 事件—— 不能用 {@link
   * Map#copyOf}（丢插入序，trace 序不可重放）。
   */
  public record Result(
      List<CandidateFinding> kept,
      Map<String, List<String>> mergedFrom,
      int mergedAway,
      int clusters) {
    public Result {
      kept = List.copyOf(kept);
      Map<String, List<String>> mergedFromCopy = new LinkedHashMap<>();
      mergedFrom.forEach((k, v) -> mergedFromCopy.put(k, List.copyOf(v)));
      mergedFrom = Collections.unmodifiableMap(mergedFromCopy);
    }
  }

  private ExactDedupMerger() {}

  /**
   * 合并同位置候选。
   *
   * @param candidates 评审波全部候选（未去重）
   * @return 保留集 + 每 kept finding 被合并掉的来源 id 表 + 合并丢弃计数
   */
  public static Result merge(List<CandidateFinding> candidates) {
    Map<String, List<CandidateFinding>> groups = new LinkedHashMap<>();
    for (CandidateFinding finding : candidates) {
      groups.computeIfAbsent(groupKey(finding), k -> new ArrayList<>()).add(finding);
    }
    List<CandidateFinding> kept = new ArrayList<>();
    Map<String, List<String>> mergedFrom = new LinkedHashMap<>();
    int mergedAway = 0;
    int clusters = 0;
    for (List<CandidateFinding> group : groups.values()) {
      group.sort(Comparator.comparingInt(CandidateFinding::line));
      List<List<CandidateFinding>> clustersInGroup = new ArrayList<>();
      List<CandidateFinding> current = new ArrayList<>();
      for (CandidateFinding finding : group) {
        if (current.isEmpty()
            || finding.line() - current.get(current.size() - 1).line() <= MERGE_RADIUS) {
          current.add(finding);
        } else {
          clustersInGroup.add(current);
          current = new ArrayList<>();
          current.add(finding);
        }
      }
      if (!current.isEmpty()) {
        clustersInGroup.add(current);
      }
      for (List<CandidateFinding> cluster : clustersInGroup) {
        clusters++;
        CandidateFinding representative = representative(cluster);
        kept.add(representative);
        if (cluster.size() > 1) {
          mergedFrom.put(
              representative.id(),
              cluster.stream()
                  .map(CandidateFinding::id)
                  .filter(id -> !id.equals(representative.id()))
                  .toList());
          mergedAway += cluster.size() - 1;
        }
      }
    }
    return new Result(kept, mergedFrom, mergedAway, clusters);
  }

  private static String groupKey(CandidateFinding finding) {
    return finding.file() + "|" + categoryOf(finding);
  }

  /** 代表元：IMPORTANT 优先 → confidence 高者（null 最低）→ 行号小者 → id 字典序（全确定性，可重放）。 */
  private static CandidateFinding representative(List<CandidateFinding> cluster) {
    return cluster.stream()
        .max(
            Comparator.<CandidateFinding>comparingInt(
                    f -> f.severityProposed() == Severity.IMPORTANT ? 1 : 0)
                .thenComparing(f -> f.confidence() == null ? -1.0 : f.confidence())
                .thenComparing(f -> -f.line())
                .thenComparing(CandidateFinding::id))
        .orElseThrow();
  }

  private static String categoryOf(CandidateFinding finding) {
    FindingCategory category = finding.category();
    return category.name().toLowerCase(Locale.ROOT);
  }
}
