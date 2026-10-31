package org.cra.eval.internal;

import org.cra.common.exception.CraConfigException;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;

/**
 * compare API 结果 → unified diff 文本（T3.5：评测集 {@code diff.patch} 需要 git unified 形态，而 DiffFile.patch
 * 只是 GitHub 的 hunk 片段，缺 {@code diff --git} 与 ---/+++ 头）。
 */
final class DiffRenderer {

  private DiffRenderer() {}

  /**
   * 渲染 unified diff。
   *
   * @param diff compare 结果
   * @return diff.patch 文本
   */
  static String render(DiffResult diff) {
    StringBuilder sb = new StringBuilder();
    for (DiffFile file : diff.files()) {
      renderFile(sb, file);
    }
    if (sb.isEmpty()) {
      throw new CraConfigException("compare 结果为空，无法生成 badcase diff.patch");
    }
    return sb.toString();
  }

  /**
   * 渲染单个文件的 unified diff 段（新增/删除/重命名/二进制形态）。
   *
   * @param sb 输出
   * @param file 文件
   */
  private static void renderFile(StringBuilder sb, DiffFile file) {
    String oldPath = file.previousPath() == null ? file.path() : file.previousPath();
    sb.append("diff --git a/").append(oldPath).append(" b/").append(file.path()).append('\n');
    if ("added".equals(file.status())) {
      sb.append("new file mode 100644\n");
    } else if ("removed".equals(file.status())) {
      sb.append("deleted file mode 100644\n");
    }
    if (file.previousPath() != null) {
      sb.append("rename from ").append(file.previousPath()).append('\n');
      sb.append("rename to ").append(file.path()).append('\n');
    }
    if (file.patch() == null || file.patch().isBlank()) {
      sb.append("Binary files a/")
          .append(oldPath)
          .append(" and b/")
          .append(file.path())
          .append(" differ\n");
      return;
    }
    if ("added".equals(file.status())) {
      sb.append("--- /dev/null\n+++ b/").append(file.path()).append('\n');
    } else if ("removed".equals(file.status())) {
      sb.append("--- a/").append(oldPath).append("\n+++ /dev/null\n");
    } else {
      sb.append("--- a/").append(oldPath).append("\n+++ b/").append(file.path()).append('\n');
    }
    sb.append(file.patch());
    if (!file.patch().endsWith("\n")) {
      sb.append('\n');
    }
  }
}
