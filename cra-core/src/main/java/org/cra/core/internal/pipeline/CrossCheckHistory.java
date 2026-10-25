package org.cra.core.internal.pipeline;

import java.util.List;
import org.cra.common.git.CommitHistoryEntry;

/**
 * CrossChecker 模式 A 的变更史输入（03§3.1 输入 b）：本地 jgit 查询结果 + 降级/局限声明。
 *
 * <p>空形态（{@link #none()}）= 未启用/未查询：available=false 且无条目——编排器不派发时不产生任何副作用。
 *
 * @param entries 上下游路径集近 N 周 commit（时间倒序；已排除本 PR 自身 commit）
 * @param available 本地仓库是否可得（false = 变更史降级，模式 A 仅用 REVIEW.md 产品段，05§2.1）
 * @param notes 覆盖声明（局限与降级，进 Summary）
 */
public record CrossCheckHistory(
    List<CommitHistoryEntry> entries, boolean available, List<String> notes) {

  public CrossCheckHistory {
    entries = List.copyOf(entries == null ? List.of() : entries);
    notes = List.copyOf(notes == null ? List.of() : notes);
  }

  /**
   * 空形态（未启用/未查询）。
   *
   * @return 空历史
   */
  public static CrossCheckHistory none() {
    return new CrossCheckHistory(List.of(), false, List.of());
  }

  /**
   * 是否有可用于模式 A 的外部数据（变更史实际命中）。本地仓库可得但零命中不构成证据——模式 A 仍可由 REVIEW.md 产品段触发（调用方另行判断）。
   *
   * @return true 若变更史有命中
   */
  public boolean hasData() {
    return !entries.isEmpty();
  }
}
