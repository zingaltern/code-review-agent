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
    {
      assertThat(ctx.localRepoRoot()).isPresent();
    }
  }

  @Test
  void emptyRootForNonRepoDirectory() {
    var ctx = new JgitLocalRepoContext(Path.of("/tmp/nonexistent-cra-test"));
    {
      assertThat(ctx.localRepoRoot()).isEmpty();
    }
  }

  @Test
  void blameTracesLineToLastModifyingCommit() {
    var ctx = new JgitLocalRepoContext(workspace);
    {
      BlameResult blame = ctx.blame("Foo.java", 1, secondCommit);
      assertThat(blame.commitSha()).isEqualTo(firstCommit);
      assertThat(blame.author()).isEqualTo("alice");
      BlameResult changed = ctx.blame("Foo.java", 2, secondCommit);
      assertThat(changed.commitSha()).isEqualTo(secondCommit);
      assertThat(changed.author()).isEqualTo("bob");
    }
  }

  @Test
  void blameMissingFileThrowsIntegrationException() {
    var ctx = new JgitLocalRepoContext(workspace);
    {
      assertThatThrownBy(() -> ctx.blame("Missing.java", 1, secondCommit))
          .isInstanceOf(CraIntegrationException.class);
    }
  }

  @Test
  void blameWithoutRepoThrowsIntegrationException() {
    var ctx = new JgitLocalRepoContext(Path.of("/tmp/nonexistent-cra-test"));
    {
      assertThatThrownBy(() -> ctx.blame("Foo.java", 1, secondCommit))
          .isInstanceOf(CraIntegrationException.class)
          .hasMessageContaining("本地仓库不可得");
    }
  }
}
