package org.cra.common.context;

import java.util.List;

/**
 * ContextAssembler 组装结果（04§1：L0 永不丢弃；丢弃决策记入 trace 并反映到 Summary 覆盖声明）。
 *
 * @param diffText L0 变更 diff（unified，带 hunk 头与行号；永不丢弃）
 * @param windowText L1 上下文（hunk 窗口/条件全文组装文本；可被裁剪为空）
 * @param filesChanged 本批变更文件数（覆盖声明输入，05§1.2 coverage）
 * @param filesReviewed 实际进入 brief 的变更文件数
 * @param skippedFiles 被跳过的文件路径（策略 skip 或无 diff 内容等，含原因后缀）
 * @param coverageNotes 覆盖声明备注（窗口/全文注入形态、切片未注入等如实说明）
 * @param estimatedTokens 组装结果估算 token（字符数 / 3.5，04§2.1）
 * @param trimDecisions 裁剪决策列表（窗口收窄/整段丢弃，逐条如实记录）
 */
public record AssembledContext(
    String diffText,
    String windowText,
    int filesChanged,
    int filesReviewed,
    List<String> skippedFiles,
    List<String> coverageNotes,
    long estimatedTokens,
    List<String> trimDecisions) {

  public AssembledContext {
    if (diffText == null || diffText.isBlank()) {
      throw new IllegalArgumentException("diffText must be non-blank");
    }
    windowText = windowText == null ? "" : windowText;
    skippedFiles = List.copyOf(skippedFiles == null ? List.of() : skippedFiles);
    coverageNotes = List.copyOf(coverageNotes == null ? List.of() : coverageNotes);
    trimDecisions = List.copyOf(trimDecisions == null ? List.of() : trimDecisions);
  }
}
