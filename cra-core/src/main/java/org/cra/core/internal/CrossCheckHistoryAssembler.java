package org.cra.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.cra.common.git.CommitHistoryEntry;
import org.cra.core.internal.pipeline.CrossCheckHistory;
import org.cra.git.api.LocalRepoContext;

/**
 * CrossChecker 变更史装配（03§3.1 输入 b；05§2.1 降级语义同 blame）。
 *
 * <p>路径集 = 本 PR 变更文件集（{@code ReviewAgentInput.scopedPaths} 同源——M2 grep 一跳依赖切片未实现， 以变更文件集作为上下游代理，
 * 如实进覆盖声明；context 召回无结构性保证是 03§3.1 的已知局限，不在实现里假装解决）。
 *
 * <p>降级：无本地仓库 / head 不可解析 / 读取失败 → available=false + 声明；模式 A 仍可用 REVIEW.md 产品段执行， 不静默整体跳过。
 */
final class CrossCheckHistoryAssembler {

  /** 局限声明（03§3.1：上下游路径集来自 grep 一跳切片，context 召回无结构性保证）。 */
  static final String SCOPE_LIMITATION_NOTE =
      "CrossChecker 上下游路径集 = 本 PR 变更文件集（依赖切片为 M2 形态）；context 召回无结构性保证（03§3.1）";

  private CrossCheckHistoryAssembler() {}

  /**
   * 查询路径集近 N 周变更史。
   *
   * @param localRepoContext 本地仓库上下文（null = 纯 API 形态）
   * @param paths 上下游路径集（本 PR 变更文件）
   * @param weeks 回溯周数
   * @param headSha 窗口锚点 commit
   * @param excludeShas 需排除的 commit（本 PR 自身 commit，避免把变更本身当外部证据）
   * @return 历史 + 声明
   */
  static CrossCheckHistory assemble(
      LocalRepoContext localRepoContext,
      Set<String> paths,
      int weeks,
      String headSha,
      Set<String> excludeShas) {
    return assemble(localRepoContext, paths, weeks, headSha, excludeShas, false);
  }

  /**
   * 查询路径集近 N 周变更史（可带语义索引增强的路径集）。
   *
   * @param indexAssisted 路径集是否含语义索引增强的关联文件（定义/引用/import 关联）——声明按来源如实区分
   */
  static CrossCheckHistory assemble(
      LocalRepoContext localRepoContext,
      Set<String> paths,
      int weeks,
      String headSha,
      Set<String> excludeShas,
      boolean indexAssisted) {
    List<String> notes = new ArrayList<>();
    String scopeNote =
        indexAssisted
            ? "CrossChecker 上下游路径集 = 本 PR 变更文件 + 语义索引关联文件（定义/引用/import；ADR-0014）"
            : SCOPE_LIMITATION_NOTE;
    if (localRepoContext == null || localRepoContext.localRepoRoot().isEmpty()) {
      notes.add(scopeNote);
      notes.add("CrossChecker 变更史不可得（无本地仓库），模式 A 仅用 REVIEW.md 产品段（05§2.1 降级声明）");
      return new CrossCheckHistory(List.of(), false, notes);
    }
    List<CommitHistoryEntry> entries;
    try {
      entries =
          localRepoContext.recentCommits(paths, weeks, headSha).stream()
              .filter(entry -> !excluded(entry, excludeShas))
              .toList();
    } catch (RuntimeException e) {
      notes.add(scopeNote);
      notes.add("CrossChecker 变更史读取失败（" + e.getClass().getSimpleName() + "），模式 A 仅用产品段");
      return new CrossCheckHistory(List.of(), false, notes);
    }
    notes.add(scopeNote);
    notes.add(
        "CrossChecker 变更史：近 "
            + weeks
            + " 周命中 "
            + entries.size()
            + " 条 commit（路径集 "
            + paths.size()
            + " 个变更文件，已排除本 PR 自身 commit）");
    return new CrossCheckHistory(entries, true, notes);
  }

  private static boolean excluded(CommitHistoryEntry entry, Set<String> excludeShas) {
    if (excludeShas == null || excludeShas.isEmpty()) {
      return false;
    }
    for (String sha : excludeShas) {
      if (sha != null && !sha.isBlank() && entry.sha().startsWith(sha)) {
        return true;
      }
    }
    return false;
  }
}
