package org.cra.common.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** hunk 区间解析（02§4.1）：合法头解析、缺省行数、非法行忽略（含超长行号的伪头——patch 属不可信输入 04§3.3）。 */
class DiffHunkTest {

  @Test
  void parsesHeadersWithExplicitAndDefaultCounts() {
    List<DiffHunk> hunks =
        DiffHunk.parseAll("@@ -48,3 +50,2 @@\n+new\n@@ -10 +11 @@\n ctx\n@@ -1,0 +1,3 @@\n+x\n");
    assertThat(hunks).hasSize(3);
    assertThat(hunks.get(0)).isEqualTo(new DiffHunk(48, 3, 50, 2));
    // 行数缺省 = 1（unified diff 对单行 hunk 可省 ",n"）
    assertThat(hunks.get(1)).isEqualTo(new DiffHunk(10, 1, 11, 1));
    assertThat(hunks.get(2)).isEqualTo(new DiffHunk(1, 0, 1, 3));
  }

  @Test
  void nonHeaderLinesAreIgnored() {
    assertThat(DiffHunk.parseAll("diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b\n"))
        .isEmpty();
    assertThat(DiffHunk.parseAll(null)).isEmpty();
    assertThat(DiffHunk.parseAll("")).isEmpty();
  }

  @Test
  void oversizedLineNumbersAreIgnoredNotExploding() {
    // 行号超 int 范围的伪 hunk 头按非法行忽略（不抛 NumberFormatException）
    assertThat(DiffHunk.parseAll("@@ -99999999999999999999,1 +1,1 @@\n+x\n")).isEmpty();
    assertThat(DiffHunk.parseAll("@@ -1,1 +99999999999999999999,1 @@\n+x\n")).isEmpty();
    // 正常行号（含 0：空文件 diff 的 +0,0 形态）不受影响
    assertThat(DiffHunk.parseAll("@@ -1,0 +0,0 @@\n").get(0)).isEqualTo(new DiffHunk(1, 0, 0, 0));
  }
}
