package org.cra.git.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.BlameResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jgit blame 行为（05§2.1；07 T1.7 pre-existing 复核的数据面）。 */
class JgitLocalRepoContextTest {

  @TempDir Path workspace;

  @TempDir Path worktreeParent;

  private String firstCommit;
  private String secondCommit;

  @BeforeEach
  void initRepo() throws Exception {
    Process p = new ProcessBuilder("git", "init", "-q").directory(workspace.toFile()).start();
    p.waitFor();
    Files.writeString(workspace.resolve("Foo.java"), "a\nb\nc\n");
    git("add", ".");
    git("-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", "first");
    firstCommit = git("rev-parse", "HEAD").strip();
    Files.writeString(workspace.resolve("Foo.java"), "a\nB2\nc\n");
    git("-c", "user.name=bob", "-c", "user.email=b@x", "commit", "-aqm", "second");
    secondCommit = git("rev-parse", "HEAD").strip();
  }

  @Test
  void recentCommitsFiltersByPathAndWindowFromHeadAnchor() throws Exception {
    // 03§3.1 模式 A：近 8 周内上下游路径集的 commit 摘要；窗口以 head commit 时间为锚（确定性）
    Path repo = Files.createDirectory(workspace.resolve("history-repo"));
    initHistory(repo);

    var ctx = new JgitLocalRepoContext(repo);
    java.util.List<org.cra.common.git.CommitHistoryEntry> hits =
        ctx.recentCommits(java.util.Set.of("Foo.java"), 8, "HEAD");

    assertThat(hits)
        .extracting(e -> e.message().strip())
        .containsExactly("head: Foo tweak", "in-window: Foo customization")
        .doesNotContain("too-old: Foo original", "other-path: readme");
    assertThat(hits.get(0).committedAt()).isAfter(hits.get(1).committedAt());
  }

  @Test
  void recentCommitsEmptyForNoPathsOrNonRepoHead() throws Exception {
    Path repo = Files.createDirectory(workspace.resolve("history-repo-empty"));
    initHistory(repo);
    var ctx = new JgitLocalRepoContext(repo);

    assertThat(ctx.recentCommits(java.util.Set.of(), 8, "HEAD")).isEmpty();
    assertThat(ctx.recentCommits(java.util.Set.of("Foo.java"), 0, "HEAD")).isEmpty();
    assertThat(ctx.recentCommits(java.util.Set.of("Foo.java"), 8, "missing-sha")).isEmpty();
    assertThat(
            new JgitLocalRepoContext(Path.of("/tmp/nonexistent-cra-test"))
                .recentCommits(java.util.Set.of("Foo.java"), 8, "HEAD"))
        .isEmpty();
  }

  /** 构造 4 个提交：过老、窗口内（命中）、其他路径、head；时间用 GIT_COMMITTER_DATE 固定。 */
  private static void initHistory(Path repo) throws Exception {
    gitIn(repo, "init", "-q");
    commitAt(repo, "2026-06-01T10:00:00+0000", "too-old: Foo original", "Foo.java", "v1\n");
    commitAt(repo, "2026-09-20T10:00:00+0000", "in-window: Foo customization", "Foo.java", "v2\n");
    commitAt(repo, "2026-09-25T10:00:00+0000", "other-path: readme", "README.md", "r\n");
    commitAt(repo, "2026-10-20T10:00:00+0000", "head: Foo tweak", "Foo.java", "v3\n");
  }

  private static void commitAt(Path repo, String date, String message, String path, String content)
      throws Exception {
    Files.writeString(repo.resolve(path), content);
    gitIn(repo, "add", ".");
    java.util.List<String> cmd =
        java.util.List.of(
            "git", "-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", message);
    ProcessBuilder builder = new ProcessBuilder(cmd).directory(repo.toFile());
    builder.environment().put("GIT_COMMITTER_DATE", date);
    builder.environment().put("GIT_AUTHOR_DATE", date);
    Process p = builder.start();
    p.waitFor();
  }

  private static void gitIn(Path repo, String... args) throws Exception {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(cmd).directory(repo.toFile()).start();
    p.waitFor();
  }

  private String git(String... args) throws Exception {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(cmd).directory(workspace.toFile()).start();
    p.waitFor();
    return new String(p.getInputStream().readAllBytes());
  }

  @Test
  void localRepoRootPresentForGitRepo() {
    var ctx = new JgitLocalRepoContext(workspace);
    assertThat(ctx.localRepoRoot()).isPresent();
  }

  @Test
  void emptyRootForNonRepoDirectory() {
    var ctx = new JgitLocalRepoContext(Path.of("/tmp/nonexistent-cra-test"));
    assertThat(ctx.localRepoRoot()).isEmpty();
  }

  @Test
  void gitWorktreeWithDotGitFileIsAccepted() throws Exception {
    // git worktree / submodule 的 .git 是 gitdir 指针文件，不是目录——仍是合法仓库
    Path worktree = worktreeParent.resolve("wt");
    git("worktree", "add", worktree.toString(), secondCommit);
    assertThat(Files.isRegularFile(worktree.resolve(".git"))).isTrue();

    var ctx = new JgitLocalRepoContext(worktree);
    assertThat(ctx.localRepoRoot()).isPresent();
    assertThat(ctx.blame("Foo.java", 1, secondCommit).commitSha()).isEqualTo(firstCommit);
  }

  @Test
  void blameTracesLineToLastModifyingCommit() {
    var ctx = new JgitLocalRepoContext(workspace);
    BlameResult blame = ctx.blame("Foo.java", 1, secondCommit);
    assertThat(blame.commitSha()).isEqualTo(firstCommit);
    assertThat(blame.author()).isEqualTo("alice");
    BlameResult changed = ctx.blame("Foo.java", 2, secondCommit);
    assertThat(changed.commitSha()).isEqualTo(secondCommit);
    assertThat(changed.author()).isEqualTo("bob");
  }

  @Test
  void blameMissingFileThrowsIntegrationException() {
    var ctx = new JgitLocalRepoContext(workspace);
    assertThatThrownBy(() -> ctx.blame("Missing.java", 1, secondCommit))
        .isInstanceOf(CraIntegrationException.class);
  }

  @Test
  void blameWithoutRepoThrowsIntegrationException() {
    var ctx = new JgitLocalRepoContext(Path.of("/tmp/nonexistent-cra-test"));
    assertThatThrownBy(() -> ctx.blame("Foo.java", 1, secondCommit))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("本地仓库不可得");
  }
}
