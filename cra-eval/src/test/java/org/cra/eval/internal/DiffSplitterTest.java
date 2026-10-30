package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** unified diff 拆分与 hunk 级测试文件过滤（06§3 净化工序①）。 */
class DiffSplitterTest {

  private static final String MULTI_FILE_DIFF =
      """
      diff --git a/src/main/java/com/app/Calc.java b/src/main/java/com/app/Calc.java
      index 111..222 100644
      --- a/src/main/java/com/app/Calc.java
      +++ b/src/main/java/com/app/Calc.java
      @@ -10,6 +10,7 @@ public class Calc {
         int size() {
      -    return n - 1;
      +    return n;
      +    // adjusted
         }
      }

      diff --git a/src/test/java/com/app/CalcTest.java b/src/test/java/com/app/CalcTest.java
      index 333..444 100644
      --- a/src/test/java/com/app/CalcTest.java
      +++ b/src/test/java/com/app/CalcTest.java
      @@ -1,4 +1,3 @@
      -import org.junit.Test;
       public class CalcTest {
      -  @Test public void gone() {}
       }

      diff --git a/src/main/java/com/app/New.java b/src/main/java/com/app/New.java
      new file mode 100644
      index 000..555
      --- /dev/null
      +++ b/src/main/java/com/app/New.java
      @@ -0,0 +1,2 @@
      +package com.app;
      +class New {}
      """;

  @Test
  void splitsIntoPerFilePatches() {
    List<DiffSplitter.PatchedFile> files = DiffSplitter.split(MULTI_FILE_DIFF);
    assertThat(files).hasSize(3);
    assertThat(files.get(0).newPath()).isEqualTo("src/main/java/com/app/Calc.java");
    assertThat(files.get(0).status()).isEqualTo("modified");
    assertThat(files.get(0).patch()).startsWith("@@ -10,6 +10,7 @@");
    assertThat(files.get(0).patch()).doesNotContain("--- a/");
    assertThat(files.get(1).status()).isEqualTo("modified");
    assertThat(files.get(2).status()).isEqualTo("added");
  }

  @Test
  void testFileDetection() {
    List<DiffSplitter.PatchedFile> files = DiffSplitter.split(MULTI_FILE_DIFF);
    assertThat(files.get(0).isTestFile()).isFalse();
    assertThat(files.get(1).isTestFile()).isTrue();
    assertThat(files.get(2).isTestFile()).isFalse();
    // maven 布局变体与命名后缀
    assertThat(DiffSplitter.isTestPath("module-core/src/test/java/A.java")).isTrue();
    assertThat(DiffSplitter.isTestPath("src/java/com/app/FooTest.java")).isTrue();
    assertThat(DiffSplitter.isTestPath("src/main/java/com/app/testpkg/Helper.java")).isFalse();
  }

  @Test
  void emptyDiffYieldsNoFiles() {
    assertThat(DiffSplitter.split("")).isEmpty();
    assertThat(DiffSplitter.split(null)).isEmpty();
  }

  @Test
  void removedFileKeepsOldPath() {
    String diff =
        """
        diff --git a/src/main/java/Old.java b/src/main/java/Old.java
        deleted file mode 100644
        index aaa..000
        --- a/src/main/java/Old.java
        +++ /dev/null
        @@ -1,1 +0,0 @@
        -class Old {}
        """;
    List<DiffSplitter.PatchedFile> files = DiffSplitter.split(diff);
    assertThat(files).hasSize(1);
    assertThat(files.get(0).status()).isEqualTo("removed");
    assertThat(files.get(0).newPath()).isEqualTo("src/main/java/Old.java");
    assertThat(files.get(0).patch()).isNotNull();
  }

  @Test
  void renamePathsDecodeCStyleQuotes() {
    // core.quotePath=true 时 rename from/to 行与 diff --git 头同样以 C 风格引号 + UTF-8 八进制字节输出
    String diff =
        """
        diff --git "a/caf\\303\\251.txt" "b/caf\\303\\251-renamed.txt"
        similarity index 100%
        rename from "caf\\303\\251.txt"
        rename to "caf\\303\\251-renamed.txt"
        """;
    List<DiffSplitter.PatchedFile> files = DiffSplitter.split(diff);
    assertThat(files).hasSize(1);
    assertThat(files.get(0).status()).isEqualTo("renamed");
    assertThat(files.get(0).oldPath()).isEqualTo("café.txt");
    assertThat(files.get(0).newPath()).isEqualTo("café-renamed.txt");
    assertThat(files.get(0).patch()).isNull();
  }
}
