package org.cra.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.FileContent;
import org.cra.context.api.ContextAssembler;
import org.junit.jupiter.api.Test;

/** 上下文组装行为（04§1：窗口 ±30 默认、条件全文 ≤200 行、裁剪阶梯、L0 永不丢弃、覆盖声明；07 T1.3）。 */
class DefaultContextAssemblerTest {

  private final ContextAssembler assembler = new DefaultContextAssembler();

  private static final String HUNDRED_LINES = hundredLines();

  private static String hundredLines() {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      sb.append("line ").append(i).append("\n");
    }
    return sb.toString();
  }

  private static DiffFile file(String path, String patch, DiffHunk... hunks) {
    return new DiffFile(path, null, "modified", patch, List.of(hunks));
  }

  private static ContextInput input(
      List<DiffFile> files,
      Map<String, FileContent> headFiles,
      int windowLines,
      boolean fullText,
      long maxTokens) {
    return new ContextInput(files, headFiles, windowLines, fullText, 200, maxTokens);
  }

  @Test
  void l0AlwaysPresentAndNeverDropped() {
    DiffFile f = file("src/A.java", "@@ -1,2 +1,3 @@\n a\n+b\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", "x")),
                30,
                true,
                1));
    assertThat(ctx.diffText()).contains("--- a/src/A.java").contains("@@ -1,2 +1,3 @@");
    assertThat(ctx.diffText()).isNotBlank();
  }

  @Test
  void windowExtractionWithHeadSideLineNumbers() {
    // hunk 起始 50 → 窗口 [50-30, 50+1-1+30] = [20, 80]，行号为 head 侧真实行号
    DiffFile f = file("src/A.java", "@@ -48,3 +50,2 @@\n+new\n", new DiffHunk(48, 3, 50, 2));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", HUNDRED_LINES)),
                30,
                false,
                10_000));
    assertThat(ctx.windowText()).contains("src/A.java（hunk 窗口 ±30）");
    assertThat(ctx.windowText()).contains("#### L20-L81");
    assertThat(ctx.windowText()).contains("L50: line 50");
    assertThat(ctx.windowText()).doesNotContain("L19: line 19");
    assertThat(ctx.trimDecisions()).isEmpty();
  }

  @Test
  void conditionalFullTextForSmallFiles() {
    DiffFile f = file("src/Small.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    String content = "a\nb\nc\n";
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/Small.java", FileContent.ofText("src/Small.java", content)),
                30,
                true,
                10_000));
    assertThat(ctx.windowText()).contains("src/Small.java（全文，3 行）");
    assertThat(ctx.windowText()).contains("L1: a").contains("L3: c");
  }

  @Test
  void largeFileFallsBackToWindows() {
    DiffFile f = file("src/Big.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/Big.java", FileContent.ofText("src/Big.java", HUNDRED_LINES)),
                30,
                true,
                10_000));
    // 100 行 ≤ 200 仍走全文；>200 才走窗口 —— 用 300 行文件验证
    DiffFile big = file("src/Big2.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx2 =
        assembler.assemble(
            input(
                List.of(big),
                Map.of(
                    "src/Big2.java",
                    FileContent.ofText(
                        "src/Big2.java", HUNDRED_LINES + HUNDRED_LINES + HUNDRED_LINES)),
                30,
                true,
                10_000));
    assertThat(ctx.windowText()).contains("全文");
    assertThat(ctx2.windowText()).contains("hunk 窗口 ±30").doesNotContain("全文");
  }

  @Test
  void missingHeadContentDegradesWithNote() {
    DiffFile deleted = new DiffFile("src/Gone.java", null, "removed", null, List.of());
    AssembledContext ctx = assembler.assemble(input(List.of(deleted), Map.of(), 30, true, 10_000));
    assertThat(ctx.skippedFiles()).anyMatch(s -> s.startsWith("src/Gone.java"));
    assertThat(ctx.windowText()).isEmpty();
    assertThat(ctx.diffText()).contains("--- a/src/Gone.java");
  }

  @Test
  void trimLadderNarrowsWindowThenDropsL1KeepingL0() {
    // 预算极小：先收窄 ±30→±15，仍超 → L1 整段丢弃；L0 保留（04§1）
    DiffFile f = file("src/A.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", HUNDRED_LINES)),
                30,
                false,
                10));
    assertThat(ctx.trimDecisions()).hasSize(2);
    assertThat(ctx.trimDecisions().get(0)).contains("±30→±15");
    assertThat(ctx.trimDecisions().get(1)).contains("L1 上下文整段丢弃");
    assertThat(ctx.diffText()).contains("--- a/src/A.java");
    assertThat(ctx.windowText()).isEmpty();
  }

  @Test
  void withinBudgetNoTrim() {
    DiffFile f = file("src/A.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", HUNDRED_LINES)),
                30,
                false,
                1_000_000));
    assertThat(ctx.trimDecisions()).isEmpty();
    assertThat(ctx.windowText()).isNotBlank();
  }

  @Test
  void coverageNotesDeclareSlicesNotInjected() {
    DiffFile f = file("src/A.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", "x\n")),
                30,
                true,
                10_000));
    assertThat(ctx.coverageNotes()).anyMatch(n -> n.contains("L2 切片未注入"));
    assertThat(ctx.filesChanged()).isEqualTo(1);
    assertThat(ctx.filesReviewed()).isEqualTo(1);
  }

  @Test
  void estimatedTokensFollowCharsDividedBy35() {
    DiffFile f = file("src/A.java", "@@ -1,2 +1,3 @@\n+x\n", new DiffHunk(1, 2, 1, 3));
    AssembledContext ctx = assembler.assemble(input(List.of(f), Map.of(), 0, false, 10_000));
    long expected = (long) Math.ceil(ctx.diffText().length() / 3.5);
    assertThat(ctx.estimatedTokens()).isEqualTo(expected);
  }

  @Test
  void adjacentHunkWindowsMerged() {
    DiffFile f =
        file(
            "src/A.java",
            "@@ -10,2 +10,2 @@\n@@ -12,2 +12,2 @@",
            new DiffHunk(10, 2, 10, 2),
            new DiffHunk(12, 2, 12, 2));
    AssembledContext ctx =
        assembler.assemble(
            input(
                List.of(f),
                Map.of("src/A.java", FileContent.ofText("src/A.java", HUNDRED_LINES)),
                5,
                false,
                10_000));
    // [5,16] 与 [7,18] 相邻重叠 → 合并为单区间
    assertThat(ctx.windowText()).contains("#### L5-L18");
    assertThat(ctx.windowText()).doesNotContain("#### L7-L");
  }
}
