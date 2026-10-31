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
      total += patchLines(f.patch());
    }
    return total;
  }

  /**
   * 单个文件 patch 的 +/- 内容行数（不含 hunk 头、上下文行与文件头）。
   *
   * @param patch 文件 patch（null 计 0）
   * @return 内容行数
   */
  private static int patchLines(String patch) {
    if (patch == null) {
      return 0;
    }
    int total = 0;
    boolean inHunk = false;
    for (String line : patch.split("\n", -1)) {
      if (line.startsWith("@@")) {
        inHunk = true;
      } else if (inHunk
          && !line.startsWith("\\") // "\ No newline at end of file"
          && (line.startsWith("+") || line.startsWith("-"))) {
        // 文件头/元数据（--- a/、+++ b/、diff --git…）只在首个 hunk 之前
        total++;
      }
    }
    return total;
  }
}
