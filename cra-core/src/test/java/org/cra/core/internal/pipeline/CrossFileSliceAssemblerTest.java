package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.junit.jupiter.api.Test;

/** L2 跨文件切片组装（ADR-0014 决策 4/7）：证据内联、变更文件不重复、预算截断声明与确定性。 */
class CrossFileSliceAssemblerTest {

  private static final String KEEP_ALIVE = "src/main/java/demo/SessionKeepAlive.java";
  private static final String CALLER = "src/main/java/demo/Caller.java";

  private static DiffFile changedFile() {
    return new DiffFile(
        KEEP_ALIVE,
        null,
        "modified",
        """
        @@ -7,3 +7,5 @@
         public class SessionKeepAlive {
        +  boolean shouldKeepAlive(Duration sessionAge) {
        +    return true;
         }""",
        List.of(new DiffHunk(7, 3, 7, 5)));
  }

  @Test
  void rendersDefinitionAndCrossFileReferenceWithFileLineEvidence() {
    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), ChangedSymbolsTest.snapshot(), 4_000);

    assertThat(slices.present()).isTrue();
    assertThat(slices.text())
        .contains("L2 跨文件上下文")
        .contains("仅 Java")
        .contains("### 符号 `shouldKeepAlive`")
        .contains("定义 `" + KEEP_ALIVE + ":9`")
        .contains("`" + CALLER + ":9`")
        .contains("L9: return keepAlive.shouldKeepAlive(age);");
    assertThat(slices.notes()).anyMatch(note -> note.contains("L2 跨文件切片："));
    assertThat(slices.truncated()).isFalse();
  }

  @Test
  void skipsReferenceSitesInsideChangedFiles() {
    // 变更文件里的引用已经由 L0/L1 覆盖，切片只补"看不到的文件"
    DiffFile callerChanged =
        new DiffFile(
            CALLER,
            null,
            "modified",
            "@@ -8,2 +8,3 @@\n   boolean check(Duration age) {\n+    boolean ok = keepAlive.shouldKeepAlive(age);\n",
            List.of(new DiffHunk(8, 2, 8, 3)));

    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile(), callerChanged), ChangedSymbolsTest.snapshot(), 4_000);

    assertThat(slices.text()).doesNotContain("L9: return keepAlive.shouldKeepAlive(age);");
  }

  @Test
  void rendersImportLinksToChangedTypes() {
    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), ChangedSymbolsTest.snapshot(), 4_000);

    // 只 import 不引用的文件也要进切片（引用位置段已覆盖的文件不重复列）
    assertThat(slices.text())
        .contains("import 关联")
        .contains("`src/main/java/demo/Loader.java:3`")
        .contains("import demo.SessionKeepAlive");
  }

  @Test
  void budgetTruncationAlwaysDeclares() {
    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), ChangedSymbolsTest.snapshot(), 120);

    if (slices.present()) {
      assertThat(slices.truncated()).isTrue();
      assertThat(slices.text()).contains("截断声明");
    }
    assertThat(slices.notes()).anyMatch(note -> note.contains("预算") || note.contains("截断"));
  }

  @Test
  void unavailableIndexYieldsEmptySlices() {
    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), SymbolIndexSnapshot.unavailable("abc", "无本地仓库"), 4_000);

    assertThat(slices.present()).isFalse();
    assertThat(slices.notes()).isEmpty();
  }

  @Test
  void noChangedSymbolsProducesExplainedEmptySlice() {
    DiffFile untouchedShape =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -1,1 +1,2 @@\n package demo;\n+// 仅注释变更\n",
            List.of(new DiffHunk(1, 1, 1, 2)));

    CrossFileSlices slices =
        CrossFileSliceAssembler.assemble(
            List.of(untouchedShape), ChangedSymbolsTest.snapshot(), 4_000);

    assertThat(slices.present()).isFalse();
    assertThat(slices.notes()).anyMatch(note -> note.contains("未注入"));
  }

  @Test
  void deterministicOutputForSameInput() {
    CrossFileSlices first =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), ChangedSymbolsTest.snapshot(), 4_000);
    CrossFileSlices second =
        CrossFileSliceAssembler.assemble(
            List.of(changedFile()), ChangedSymbolsTest.snapshot(), 4_000);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void sliceRolesAreDataflowSecurityAndVerifierOnly() {
    assertThat(CrossFileSliceAssembler.isSliceRole("bug-hunter/dataflow")).isTrue();
    assertThat(CrossFileSliceAssembler.isSliceRole("security-reviewer")).isTrue();
    assertThat(CrossFileSliceAssembler.isSliceRole("verifier")).isTrue();
    assertThat(CrossFileSliceAssembler.isSliceRole("bug-hunter/boundary")).isFalse();
    assertThat(CrossFileSliceAssembler.isSliceRole("perf-reviewer")).isFalse();
    assertThat(CrossFileSliceAssembler.isSliceRole("cross-checker")).isFalse();
  }
}
