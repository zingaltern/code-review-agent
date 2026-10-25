package org.cra.common.context;

import java.util.List;
import java.util.Map;
import org.cra.common.git.DiffFile;
import org.cra.common.git.FileContent;

/**
 * ContextAssembler 单次组装输入（cra-context 不发起平台调用——文件全文由编排器预取后内联，10§2 cra-context 行）。
 *
 * @param files 本批组装的变更文件（chunk 范围；skip/priority 范围裁决由编排器在规划期完成）
 * @param headFiles head 侧文件全文，按路径索引（删除/二进制文件键不存在）
 * @param windowLines L1 hunk 窗口行数（±N，04§1 默认 30、大 diff 降档 15、deep 档更宽）
 * @param conditionalFullText 条件全文开关（04§1：文件行数 ≤ fullFileMaxLines 时注入全文）
 * @param fullFileMaxLines 条件全文的文件行数上限（04§1 默认 200）
 * @param maxContextTokens L1 层 token 预算（超限触发裁剪阶梯；L0 永不丢弃）
 */
public record ContextInput(
    List<DiffFile> files,
    Map<String, FileContent> headFiles,
    int windowLines,
    boolean conditionalFullText,
    int fullFileMaxLines,
    long maxContextTokens) {

  public ContextInput {
    files = List.copyOf(files == null ? List.of() : files);
    headFiles = headFiles == null ? Map.of() : Map.copyOf(headFiles);
    if (windowLines < 0) {
      throw new IllegalArgumentException("windowLines must be non-negative: " + windowLines);
    }
    if (fullFileMaxLines <= 0) {
      throw new IllegalArgumentException("fullFileMaxLines must be positive: " + fullFileMaxLines);
    }
    if (maxContextTokens <= 0) {
      throw new IllegalArgumentException("maxContextTokens must be positive: " + maxContextTokens);
    }
  }
}
