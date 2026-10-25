package org.cra.common.git;

import java.util.List;

/**
 * compare API 取回的完整变更集（GitPlatformAdapter.fetchDiff 返回值，05§2.1）。
 *
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 * @param files 变更文件列表（不含未变更文件）
 * @param commits 变更集内 commit 摘要列表
 */
public record DiffResult(
    String baseSha, String headSha, List<DiffFile> files, List<CommitSummary> commits) {

  public DiffResult {
    files = List.copyOf(files == null ? List.of() : files);
    commits = List.copyOf(commits == null ? List.of() : commits);
  }

  /**
   * 变更 diff 总行数（+/- 行合计，大 diff 降档判据输入，02§4.4）。
   *
   * @return 所有文件 patch 行数之和（patch 为 null 的文件计 0）
   */
  public int totalPatchLines() {
    int total = 0;
    for (DiffFile f : files) {
      if (f.patch() != null) {
        total += f.patch().split("\n", -1).length;
      }
    }
    return total;
  }
}
