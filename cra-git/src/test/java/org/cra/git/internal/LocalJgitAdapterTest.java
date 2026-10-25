package org.cra.git.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.git.api.LocalGitAdapter;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 jgit 适配器单测（T2.10；08§2.2：@TempDir 真实 git 仓库，零网络）。锁定 patch 与 GitHub compare 同构（hunk
 * 解析/状态映射）、readFile 严格 UTF-8、ref 解析与 no-op 发布语义。
 */
class LocalJgitAdapterTest {

  @TempDir private Path tempDir;

  @TempDir private Path nonRepoDir;

  private Git git;
  private LocalGitAdapter adapter;
  private String baseSha;

  @BeforeEach
  void setUp() throws Exception {
    git = Git.init().setDirectory(tempDir.toFile()).call();
    configIdentity();
    write("src/Foo.java", "package demo;\n\npublic class Foo {\n}\n");
    write("KEEP.md", "keep\n");
    git.add().addFilepattern(".").call();
    baseSha = commit("base: initial");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    write(
        "src/Foo.java", "package demo;\n\npublic class Foo {\n  int bad() { return 1 / 0; }\n}\n");
    write("src/Bar.java", "package demo;\n\npublic class Bar {\n}\n");
    Files.delete(tempDir.resolve("KEEP.md"));
    git.add().addFilepattern(".").call();
    commit("feat: change foo, add bar, remove keep");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (adapter != null) {
      adapter.close();
    }
    if (git != null) {
      git.close();
    }
  }

  private void configIdentity() {
    git.getRepository().getConfig().setString("user", null, "name", "tester");
    git.getRepository().getConfig().setString("user", null, "email", "tester@example.com");
  }

  private void write(String path, String content) throws Exception {
    Path file = tempDir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private String commit(String message) throws Exception {
    return git.commit().setMessage(message).call().getName();
  }

  @Test
  void fetchDiffProducesGitHubCompatibleFiles() {
    adapter = new LocalJgitAdapter(tempDir);
    DiffResult diff = adapter.fetchDiff(new RepoRef("o", "r"), baseSha, "feature");

    assertThat(diff.files()).hasSize(3);
    DiffFile foo =
        diff.files().stream()
            .filter(f -> f.path().equals("src/Foo.java"))
            .findFirst()
            .orElseThrow();
    assertThat(foo.status()).isEqualTo("modified");
    assertThat(foo.patch()).contains("@@ -").contains("+  int bad() { return 1 / 0; }");
    assertThat(foo.hunks()).isNotEmpty();
    assertThat(foo.containsLine(Side.RIGHT, 4)).isTrue(); // +int bad() 在 hunk 内
    assertThat(foo.containsLine(Side.RIGHT, 400)).isFalse();

    DiffFile bar =
        diff.files().stream()
            .filter(f -> f.path().equals("src/Bar.java"))
            .findFirst()
            .orElseThrow();
    assertThat(bar.status()).isEqualTo("added");

    DiffFile keep =
        diff.files().stream().filter(f -> f.path().equals("KEEP.md")).findFirst().orElseThrow();
    assertThat(keep.status()).isEqualTo("removed");
    assertThat(keep.patch()).isNotNull(); // 文本删除文件有 patch

    assertThat(diff.commits()).hasSize(1);
    assertThat(diff.commits().get(0).message()).isEqualTo("feat: change foo, add bar, remove keep");
    assertThat(diff.headSha()).isNotEqualTo(baseSha);
  }

  @Test
  void readFileResolvesShaAndDetectsBinary() throws Exception {
    adapter = new LocalJgitAdapter(tempDir);
    String head = adapter.headSha();
    FileContent foo = adapter.readFile(new RepoRef("o", "r"), head, "src/Foo.java");
    assertThat(foo.binary()).isFalse();
    assertThat(foo.text()).contains("bad()");

    FileContent old = adapter.readFile(new RepoRef("o", "r"), baseSha, "src/Foo.java");
    assertThat(old.text()).doesNotContain("bad()");

    // 非 UTF-8 字节 → binary 语义（text=null）
    Files.write(tempDir.resolve("bin.dat"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00});
    git.add().addFilepattern(".").call();
    String binSha = commit("add binary");
    assertThat(adapter.readFile(new RepoRef("o", "r"), binSha, "bin.dat").binary()).isTrue();

    assertThatThrownBy(() -> adapter.readFile(new RepoRef("o", "r"), head, "no/such.java"))
        .isInstanceOf(CraIntegrationException.class);
  }

  @Test
  void resolveRejectsUnknownRef() {
    adapter = new LocalJgitAdapter(tempDir);
    assertThat(adapter.resolve("feature")).isEqualTo(adapter.headSha());
    assertThatThrownBy(() -> adapter.resolve("no-such-branch"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("no-such-branch");
  }

  @Test
  void remoteOriginOwnerSlashNameParsesHttpsAndScpForms() throws Exception {
    adapter = new LocalJgitAdapter(tempDir);
    assertThat(adapter.remoteOriginOwnerSlashName()).isEmpty(); // 无 origin

    git.getRepository()
        .getConfig()
        .setString(
            "remote", "origin", "url", "https://github.com/zingaltern/code-review-agent.git");
    git.getRepository().getConfig().save();
    adapter.close();
    adapter = new LocalJgitAdapter(tempDir);
    assertThat(adapter.remoteOriginOwnerSlashName()).contains("zingaltern/code-review-agent");

    git.getRepository()
        .getConfig()
        .setString("remote", "origin", "url", "git@github.com:zingaltern/cra.git");
    git.getRepository().getConfig().save();
    adapter.close();
    adapter = new LocalJgitAdapter(tempDir);
    assertThat(adapter.remoteOriginOwnerSlashName()).contains("zingaltern/cra");
  }

  @Test
  void postReviewIsNoopConfirmWithZeroSummaryId() {
    adapter = new LocalJgitAdapter(tempDir);
    PrReviewResult result =
        adapter.postReview(
            new PostReviewCommand(
                "o",
                "r",
                0,
                baseSha,
                "本地评审引导语",
                List.of(new InlineComment("src/Foo.java", 4, Side.RIGHT, "body", "f_1")),
                "summary"));
    assertThat(result.summaryCommentId()).contains(0L);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).publishStatus()).isEqualTo(PublishStatus.PUBLISHED);
    assertThat(result.items().get(0).anchorStatus()).isEqualTo(AnchorStatus.INLINE);
    assertThat(result.items().get(0).commentId()).isNull();
  }

  @Test
  void nonRepoDirectoryFailsAtConstruction() {
    // 独立非仓库目录：FileRepositoryBuilder 向上搜索 .git，不得命中 fixture 仓库
    assertThatThrownBy(() -> new LocalJgitAdapter(nonRepoDir.resolve("nested")))
        .isInstanceOf(CraIntegrationException.class);
  }
}
