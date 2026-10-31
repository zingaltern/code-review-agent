package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.RepoRef;
import org.cra.eval.api.FixtureMeta;
import org.cra.git.api.GitPlatformAdapter;

/** badcase 产物落盘（T3.5）：diff 取数、仓库快照、meta 组装与半成品清理。 */
final class BadcaseFixtureWriter {

  private BadcaseFixtureWriter() {}

  static String diff(
      BadcaseService.Inputs inputs,
      GitPlatformAdapter adapter,
      RepoRef repo,
      TraceIngest.RunTrace run) {
    if (inputs.diffFile() != null) {
      try {
        String text = Files.readString(inputs.diffFile(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
          throw new CraConfigException("--diff-file 为空: " + inputs.diffFile());
        }
        return text;
      } catch (IOException e) {
        throw new CraConfigException("--diff-file 读取失败: " + inputs.diffFile());
      }
    }
    requireRemote(adapter, repo, run, "diff");
    if (run.baseSha().isBlank() || run.headSha().isBlank()) {
      throw new CraConfigException("run trace 缺 base/head sha，无法拉 diff: " + run.runId());
    }
    return DiffRenderer.render(adapter.fetchDiff(repo, run.baseSha(), run.headSha()));
  }

  static SnapshotBuilder.Result snapshot(
      BadcaseService.Inputs inputs,
      GitPlatformAdapter adapter,
      RepoRef repo,
      TraceIngest.RunTrace run,
      Path caseDir) {
    if (inputs.snapshotDir() != null) {
      return SnapshotBuilder.fromLocalDir(caseDir, inputs.snapshotDir());
    }
    requireRemote(adapter, repo, run, "快照");
    if (run.headSha().isBlank()) {
      throw new CraConfigException("run trace 缺 head sha，无法拉快照: " + run.runId());
    }
    return SnapshotBuilder.fromRemote(caseDir, repo, run.headSha(), adapter);
  }

  static FixtureMeta meta(
      BadcaseService.Inputs inputs,
      RepoRef repo,
      TraceIngest.RunTrace run,
      String caseId,
      String set,
      SnapshotBuilder.Result snapshot,
      int changedFiles) {
    String owner = repo == null ? "local" : repo.owner();
    String name = repo == null ? BadcaseIdentifiers.sanitize(run.runId()) : repo.repo();
    String note =
        "badcase "
            + inputs.label()
            + " run="
            + run.runId()
            + " finding="
            + inputs.findingId()
            + " diff_files="
            + changedFiles
            + " snapshot_files="
            + snapshot.files()
            + (snapshot.skippedBinary() > 0 ? " binary_skipped=" + snapshot.skippedBinary() : "")
            + (inputs.reason() == null || inputs.reason().isBlank() ? "" : " | " + inputs.reason());
    return new FixtureMeta(
        FixtureMeta.FORMAT_VERSION,
        caseId,
        set,
        null,
        "production",
        null,
        null,
        repo == null ? "" : owner + "/" + name + "#" + run.pr(),
        run.baseSha(),
        run.headSha(),
        new FixtureMeta.Snapshot("repo_snapshot.tgz", snapshot.sha256(), "."),
        new FixtureMeta.ReviewRequestSpec(Math.max(run.pr(), 1), owner, name),
        List.of(),
        new FixtureMeta.Annotation(
            "draft",
            inputs.annotator(),
            java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()),
        new FixtureMeta.Recheck("pending", "", ""),
        note);
  }

  /** 半成品清理：清理失败挂到原始异常（suppressed），不掩盖主错误。 */
  static void cleanup(Path root, Throwable primary) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      primary.addSuppressed(e);
    }
  }

  private static void requireRemote(
      GitPlatformAdapter adapter, RepoRef repo, TraceIngest.RunTrace run, String what) {
    if (adapter == null || repo == null) {
      throw new CraConfigException(
          "生成 "
              + what
              + " 需要 GitHub token（--token/GITHUB_TOKEN）或离线输入（"
              + ("diff".equals(what) ? "--diff-file" : "--snapshot-dir")
              + "）；run repo="
              + run.repo());
    }
  }
}
