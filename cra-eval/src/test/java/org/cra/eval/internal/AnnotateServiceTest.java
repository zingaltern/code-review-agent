package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** annotate 预填与 relabel 通道格式（06§3 半自动标注 + 净化工序②预留）。 */
class AnnotateServiceTest {

  @TempDir Path tmp;

  private static final String DIFF =
      """
      diff --git a/src/main/java/Calc.java b/src/main/java/Calc.java
      index aaa..bbb 100644
      --- a/src/main/java/Calc.java
      +++ b/src/main/java/Calc.java
      @@ -10,7 +10,8 @@ class Calc {
         int size() {
      -    return items.size() - 1;
      +    if (items.isEmpty()) { throw new IllegalStateException("empty items list"); }
      +    return items.size() - 1;
         }

      diff --git a/src/test/java/CalcTest.java b/src/test/java/CalcTest.java
      index ccc..ddd 100644
      --- a/src/test/java/CalcTest.java
      +++ b/src/test/java/CalcTest.java
      @@ -1,3 +1,4 @@
       class CalcTest {
      +  void removed() {}
       }
      """;

  @Test
  void prefillSkipsTestFilesAndExtractsKeywords() throws Exception {
    Path caseDir = tmp.resolve("bad-X-1");
    Files.createDirectories(caseDir);
    Files.writeString(caseDir.resolve("diff.patch"), DIFF);
    Path draft = AnnotateService.prefill(caseDir, null, "tester");
    assertThat(draft.getFileName().toString()).isEqualTo("expected_findings.draft.json");
    ExpectedFindings parsed = Json.readExpected(draft);
    // 仅 main 侧 1 个 hunk 预填；test hunk 被跳过
    assertThat(parsed.expected()).hasSize(1);
    assertThat(parsed.expected().get(0).file()).isEqualTo("src/main/java/Calc.java");
    assertThat(parsed.expected().get(0).line()).isEqualTo(10);
    assertThat(parsed.expected().get(0).category()).isEqualTo("bug");
    assertThat(parsed.expected().get(0).keywords()).contains("IllegalStateException");
    assertThat(parsed.negativeBaits()).isEmpty();
  }

  @Test
  void confirmMovesDraftAndAdvancesMetaStatus() throws Exception {
    Path caseDir = tmp.resolve("bad-X-2");
    Files.createDirectories(caseDir);
    Files.writeString(caseDir.resolve("diff.patch"), DIFF);
    AnnotateService.prefill(caseDir, null, "tester");
    Path metaFile = caseDir.resolve("meta.json");
    Files.writeString(
        metaFile,
        """
        {"format_version":1,"case_id":"bad-X-2","set":"bad","source":"defects4j",
         "base_sha":"%s","head_sha":"%s",
         "snapshot":{"file":"repo_snapshot.tgz"},
         "review_request":{"pr_number":9001,"repo_owner":"o","repo_name":"bad-X-2"},
         "annotation":{"status":"draft","annotator":"gen","annotated_at":"2026-10-25"},
         "recheck":{"status":"pending"}}
        """
            .formatted("a".repeat(40), "b".repeat(40)));
    AnnotateService.confirm(caseDir, "human-1");
    assertThat(caseDir.resolve("expected_findings.json")).exists();
    assertThat(caseDir.resolve("expected_findings.draft.json")).doesNotExist();
    FixtureMeta meta = Json.readMeta(metaFile);
    assertThat(meta.annotation().status()).isEqualTo("annotated");
    assertThat(meta.annotation().annotator()).isEqualTo("human-1");
  }

  @Test
  void relabelWritesRecheckChannelFormat() throws Exception {
    Path caseDir = tmp.resolve("clean-Y-1");
    Files.createDirectories(caseDir);
    Files.writeString(
        caseDir.resolve("meta.json"),
        """
        {"format_version":1,"case_id":"clean-Y-1","set":"clean","source":"defects4j",
         "base_sha":"%s","head_sha":"%s",
         "snapshot":{"file":"repo_snapshot.tgz"},
         "review_request":{"pr_number":9001,"repo_owner":"o","repo_name":"clean-Y-1"},
         "annotation":{"status":"annotated","annotator":"gen","annotated_at":"2026-10-25"},
         "recheck":{"status":"pending"}}
        """
            .formatted("a".repeat(40), "b".repeat(40)));
    AnnotateService.relabel(caseDir, "bad", "human-2", "快筛误标：实含缺陷");
    FixtureMeta meta = Json.readMeta(caseDir.resolve("meta.json"));
    assertThat(meta.set()).isEqualTo("bad");
    assertThat(meta.recheck().status()).isEqualTo("relabeled");
    assertThat(meta.recheck().relabeledTo()).isEqualTo("bad");
    assertThat(meta.annotation().status()).isEqualTo("relabeled");
  }
}
