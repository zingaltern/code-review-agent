package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 数据集加载器与快照缓存（06§3 fixture 规格 v1）。 */
class DatasetLoaderTest {

  @TempDir Path tmp;

  private static final String SNAPSHOT_FILE = "src/main/java/Demo.java";

  @Test
  void loadsCaseUnpacksSnapshotAndVerifiesSha() throws Exception {
    Path caseDir = tmp.resolve("dataset/bad/bad-Demo-1");
    Files.createDirectories(caseDir);
    Files.createDirectories(caseDir.resolve("src/main/java"));
    Files.writeString(caseDir.resolve(SNAPSHOT_FILE), "class Demo {}\n", StandardCharsets.UTF_8);
    Path tgz = tmp.resolve("snap.tgz");
    makeTgz(caseDir, SNAPSHOT_FILE, tgz);
    String sha = SnapshotStore.sha256(tgz);
    Files.copy(tgz, caseDir.resolve("repo_snapshot.tgz"));
    Files.writeString(caseDir.resolve("diff.patch"), sampleDiff(), StandardCharsets.UTF_8);
    writeMeta(caseDir, sha);
    Files.writeString(
        caseDir.resolve("expected_findings.json"),
        "{\"format_version\":1,\"expected\":[],\"negative_baits\":[]}",
        StandardCharsets.UTF_8);

    DatasetLoader.LoadedCase loaded = DatasetLoader.load(caseDir, tmp.resolve("cache"));
    assertThat(loaded.meta().caseId()).isEqualTo("bad-Demo-1");
    assertThat(loaded.snapshotRoot().resolve(SNAPSHOT_FILE)).hasContent("class Demo {}\n");
    // 幂等：同 sha 二次加载复用缓存
    DatasetLoader.LoadedCase again = DatasetLoader.load(caseDir, tmp.resolve("cache"));
    assertThat(again.snapshotRoot()).isEqualTo(loaded.snapshotRoot());
  }

  @Test
  void missingSnapshotGivesActionableError() throws Exception {
    Path caseDir = tmp.resolve("dataset/bad/bad-Demo-2");
    Files.createDirectories(caseDir);
    writeMeta(caseDir, "deadbeef");
    Files.writeString(
        caseDir.resolve("expected_findings.json"),
        "{\"format_version\":1,\"expected\":[]}",
        StandardCharsets.UTF_8);
    Path cache = tmp.resolve("cache");
    assertThatThrownBy(() -> DatasetLoader.load(caseDir, cache))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("repo_snapshot.tgz 不入 git")
        .hasMessageContaining("README");
  }

  @Test
  void shaMismatchIsRejected() throws Exception {
    Path caseDir = tmp.resolve("dataset/bad/bad-Demo-3");
    Files.createDirectories(caseDir);
    Path staging = tmp.resolve("staging3");
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("x.txt"), "payload", StandardCharsets.UTF_8);
    Path tgz = tmp.resolve("snap3.tgz");
    makeTgz(staging, "x.txt", tgz);
    Files.copy(tgz, caseDir.resolve("repo_snapshot.tgz"));
    writeMeta(caseDir, "0000000000000000000000000000000000000000000000000000000000000000");
    Files.writeString(caseDir.resolve("diff.patch"), sampleDiff(), StandardCharsets.UTF_8);
    Files.writeString(
        caseDir.resolve("expected_findings.json"),
        "{\"format_version\":1,\"expected\":[]}",
        StandardCharsets.UTF_8);
    Path cache = tmp.resolve("cache");
    assertThatThrownBy(() -> DatasetLoader.load(caseDir, cache))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("sha256 不一致");
  }

  @Test
  void listCasesFiltersBySet() throws Exception {
    Path root = tmp.resolve("ds");
    for (String name : new String[] {"bad/bad-A-1", "bad/bad-B-2", "clean/clean-C-3"}) {
      Path d = root.resolve(name);
      Files.createDirectories(d);
      writeMeta(d, null, name.startsWith("bad/") ? "bad" : "clean");
    }
    // 无 meta 的目录不入选
    Files.createDirectories(root.resolve("bad/broken"));
    assertThat(DatasetLoader.listCases(root, "bad")).hasSize(2);
    assertThat(DatasetLoader.listCases(root, "clean")).hasSize(1);
    assertThat(DatasetLoader.listCases(root, "l2")).hasSize(3); // L2 口径 = bad + clean（04§2.4）
    assertThat(DatasetLoader.listCases(root, null)).hasSize(3);
    assertThat(DatasetLoader.listCases(root, "all")).hasSize(3);
    assertThatThrownBy(() -> DatasetLoader.listCases(root, "nope"))
        .isInstanceOf(CraConfigException.class);
  }

  // ---------- 工具 ----------

  private void writeMeta(Path caseDir, String sha) throws Exception {
    writeMeta(caseDir, sha, "bad");
  }

  private void writeMeta(Path caseDir, String sha, String set) throws Exception {
    Files.writeString(
        caseDir.resolve("meta.json"),
        """
            {
              "format_version": 1,
              "case_id": "%s",
              "set": "%s",
              "source": "defects4j",
              "project": "Demo",
              "bug_id": 1,
              "base_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "head_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "snapshot": {"file": "repo_snapshot.tgz", "sha256": %s, "crop_root": "src/main/java"},
              "review_request": {"pr_number": 9001, "repo_owner": "cra-eval-fixture", "repo_name": "bad-Demo"},
              "filtered_test_files": [],
              "annotation": {"status": "annotated", "annotator": "test", "annotated_at": "2026-10-25"},
              "recheck": {"status": "pending"}
            }
            """
            .formatted(caseDir.getFileName(), set, sha == null ? "null" : "\"" + sha + "\""),
        StandardCharsets.UTF_8);
  }

  private void makeTgz(Path contentRoot, String relativePath, Path outTgz) throws Exception {
    // 用系统 tar 打包（macOS bsdtar / Linux GNU tar 均可），与生成工具同一 tar 形态
    Process p =
        new ProcessBuilder(
                "tar", "-czf", outTgz.toString(), "-C", contentRoot.toString(), relativePath)
            .inheritIO()
            .start();
    assertThat(p.waitFor()).isZero();
  }

  private String sampleDiff() {
    return String.join(
        "\n",
        "diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java",
        "index aaa..bbb 100644",
        "--- a/src/main/java/Demo.java",
        "+++ b/src/main/java/Demo.java",
        "@@ -1,1 +1,2 @@",
        " class Demo {}",
        "+// added line",
        "");
  }
}
