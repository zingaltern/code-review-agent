package org.cra.context.internal;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;
import org.cra.common.context.TokenMath;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.FileContent;
import org.cra.context.api.ContextAssembler;

/**
 * 默认上下文组装（04§1 分层模型 M1 形态）：
 *
 * <ul>
 *   <li>L0：unified diff 全文，永不丢弃；
 *   <li>L1：hunk ±N 窗口（默认 30）或条件全文（文件 ≤ fullFileMaxLines 行）；裁剪阶梯 = 窗口收窄 ±30→±15 → L1 整段丢弃（M1 无 L2
 *       切片与 light 角色，后两档映射为 L1 收窄与整段丢弃——L0 永不丢弃）；
 *   <li>L2：按需切片 M1 无角色路由需求，不注入（覆盖声明如实说明）；
 *   <li>窗口与全文均带 head 侧行号前缀（evidence-rules 要求行号证据）。
 * </ul>
 */
public final class DefaultContextAssembler implements ContextAssembler {

  /** 窗口收窄档位（04§1：±30→±15；按输入窗口减半，下限 15）。 */
  private static final int NARROW_FLOOR = 15;

  @Override
  public AssembledContext assemble(ContextInput input) {
    StringBuilder l0 = new StringBuilder();
    for (DiffFile file : input.files()) {
      l0.append("--- a/").append(file.path()).append("\n+++ b/").append(file.path());
      if (file.patch() != null && !file.patch().isBlank()) {
        l0.append("\n").append(file.patch());
      }
      l0.append("\n");
    }
    String diffText = l0.toString().stripTrailing();

    List<String> trimDecisions = new ArrayList<>();
    List<String> coverageNotes = new ArrayList<>();
    List<String> skippedFiles = new ArrayList<>();
    String l1 = buildL1(input, coverageNotes, skippedFiles);

    long budget = input.maxContextTokens();
    long l1Tokens = TokenMath.estimateTokens(l1);
    if (l1Tokens > budget && !l1.isEmpty()) {
      int narrowed = Math.max(NARROW_FLOOR, input.windowLines() / 2);
      if (narrowed < input.windowLines()) {
        String narrowedText =
            buildL1(withWindow(input, narrowed), new ArrayList<>(), new ArrayList<>());
        trimDecisions.add(
            "L1 窗口收窄 ±%d→±%d（估算 %d token 超预算 %d）"
                .formatted(input.windowLines(), narrowed, l1Tokens, budget));
        l1 = narrowedText;
        l1Tokens = TokenMath.estimateTokens(l1);
      }
      if (l1Tokens > budget && !l1.isEmpty()) {
        trimDecisions.add(
            "L1 上下文整段丢弃（收窄后仍估算 %d token 超预算 %d；L0 diff 保留）".formatted(l1Tokens, budget));
        l1 = "";
        l1Tokens = 0;
      }
    }

    if (input.conditionalFullText()) {
      coverageNotes.add("L2 切片未注入（M1 单角色无按需路由，机制随 M2 视角接入）");
    }
    long totalTokens = TokenMath.estimateTokens(diffText) + l1Tokens;
    return new AssembledContext(
        diffText,
        l1,
        input.files().size(),
        input.files().size(),
        skippedFiles,
        coverageNotes,
        totalTokens,
        trimDecisions);
  }

  private ContextInput withWindow(ContextInput input, int windowLines) {
    return new ContextInput(
        input.files(),
        input.headFiles(),
        windowLines,
        input.conditionalFullText(),
        input.fullFileMaxLines(),
        input.maxContextTokens());
  }

  /** L1 组装：条件全文优先，否则 hunk 窗口（重叠区间合并）；无 head 内容的文件降级说明。 */
  private String buildL1(
      ContextInput input, List<String> coverageNotes, List<String> skippedFiles) {
    if (!input.conditionalFullText() && input.windowLines() <= 0) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (DiffFile file : input.files()) {
      FileContent content = input.headFiles().get(file.path());
      List<String> lines = textLines(content);
      if (lines == null) {
        skippedFiles.add(file.path() + "（无 head 文件内容：" + describe(file) + "）");
        continue;
      }
      if (input.conditionalFullText() && lines.size() <= input.fullFileMaxLines()) {
        out.append("### ").append(file.path()).append("（全文，").append(lines.size()).append(" 行）\n");
        out.append(numbered(lines, 1));
        continue;
      }
      if (file.hunks().isEmpty()) {
        continue;
      }
      List<int[]> ranges = mergeRanges(file.hunks(), input.windowLines(), lines.size());
      out.append("### ")
          .append(file.path())
          .append("（hunk 窗口 ±")
          .append(input.windowLines())
          .append("）\n");
      for (int[] range : ranges) {
        out.append("#### L").append(range[0]).append("-L").append(range[1]).append("\n");
        out.append(numbered(lines.subList(range[0] - 1, range[1]), range[0]));
      }
    }
    return out.toString().stripTrailing();
  }

  private static String describe(DiffFile file) {
    if (file.patch() == null) {
      return "二进制或无 diff 内容";
    }
    return "status=" + file.status();
  }

  private static List<String> textLines(FileContent content) {
    if (content == null || content.binary() || content.text() == null) {
      return null;
    }
    String text = content.text();
    // 文件末尾换行产生的幻影空行不参与展示（finding.line 直接索引原文行，指纹锚行语义不受影响）
    String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    return List.of(body.split("\n", -1));
  }

  /** hunk 扩窗后合并重叠区间，返回 [start, end]（1 基，闭区间）。 */
  private static List<int[]> mergeRanges(List<DiffHunk> hunks, int window, int lineCount) {
    List<int[]> ranges = new ArrayList<>();
    for (DiffHunk hunk : hunks) {
      int start = Math.max(1, hunk.newStart() - window);
      int end = Math.min(lineCount, hunk.newStart() + Math.max(hunk.newLines(), 1) - 1 + window);
      if (end < start) {
        end = start;
      }
      if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1] + 1) {
        int[] last = ranges.get(ranges.size() - 1);
        last[1] = Math.max(last[1], end);
      } else {
        ranges.add(new int[] {start, end});
      }
    }
    return ranges;
  }

  /** 行号前缀渲染（1 基），与 evidence-rules 的 L&lt;n&gt;: 引用形态一致。 */
  private static String numbered(List<String> lines, int startLine) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      out.append("L").append(startLine + i).append(": ").append(lines.get(i)).append("\n");
    }
    return out.toString();
  }
}
