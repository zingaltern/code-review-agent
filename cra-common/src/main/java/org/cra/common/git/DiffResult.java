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
   * <p>只计增删内容行：patch 文本还含 hunk 头与上下文行，按文本行计会把 150 增删行的小 PR 算成数百行， 使 auto-quick 与大 diff 降档在中型 PR
   * 上误触发。
   *
   * @return 所有文件 patch 的 +/- 内容行数之和（patch 为 null 的文件计 0）
   */
  public int totalPatchLines() {
    int total = 0;
    for (DiffFile f : files) {
      if (f.patch() == null) {
        continue;
      }
      boolean inHunk = false;
      for (String line : f.patch().split("\n", -1)) {
        if (line.startsWith("@@")) {
          inHunk = true;
          continue;
        }
        if (!inHunk) {
          continue; // 文件头/元数据（--- a/、+++ b/、diff --git…）只在首个 hunk 之前
        }
        if (line.startsWith("\\")) {
          continue; // "\ No newline at end of file"
        }
        if (line.startsWith("+") || line.startsWith("-")) {
          total++;
        }
      }
    }
    return total;
  }
}
