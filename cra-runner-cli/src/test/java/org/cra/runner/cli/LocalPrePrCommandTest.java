package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.cra.common.exception.CraIntegrationException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 pre-PR 命令流程（07 T2.10 快侧）：真实 git 仓库 + 真实子进程实跑，无 LLM 段（无密钥如实跳过）。 验收 8 的 LLM 全链路版本见 {@code
 * LocalPrePrEndToEndTest}（@Tag("slow")，按需）。
 */
class LocalPrePrCommandTest {

  @TempDir private Path workspace;

  @TempDir private Path runRoot;

  private Git git;
  private String branch;

  @BeforeEach
  void setUp() throws Exception {
    git = Git.init().setDirectory(workspace.toFile()).call();
    git.getRepository().getConfig().setString("user", null, "name", "tester");
    git.getRepository().getConfig().setString("user", null, "email", "t@t");
    Files.writeString(workspace.resolve("README.md"), "demo\n");
    git.add().addFilepattern(".").call();
    git.commit().setMessage("init").call();
    branch = git.getRepository().getBranch(); // jgit 默认分支随版本（master/main）
  }

  @AfterEach
  void tearDown() {
    if (git != null) {
      git.close();
    }
  }

  private CraConfig parse(String... args) {
    String[] head = {
      "--local",
      "--base",
      branch,
      "--workspace",
      workspace.toString(),
      "--run-root",
      runRoot.toString()
    };
    String[] all = new String[head.length + args.length];
    System.arraycopy(head, 0, all, 0, head.length);
    System.arraycopy(args, 0, all, head.length, args.length);
    return CliArgs.parse(all, Map.of());
  }

  @Test
  void failingExecReturnsExitCodeFour() {
    // 验收 8 单测侧：实跑失败 → 退出码非零（4），实跑日志落盘留证
    CraConfig config = parse(new String[] {"--exec", "/usr/bin/false", "--ledger-db", "l.db"});
    int code = new LocalPrePrCommand().execute(config);
    assertThat(code).isEqualTo(LocalPrePrCommand.EXIT_BUILD_FAILED);
    try (var files = Files.list(runRoot)) {
      assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("build-"))).isTrue();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void passingExecWithNoLlmKeySkipsReviewHonestly() {
    // exec 值含空格 = 单个参数（空白分词语义在 BuildVerifyRunner 内）
    CraConfig config = parse(new String[] {"--exec", "/bin/echo ok", "--ledger-db", "l.db"});
    int code = new LocalPrePrCommand().execute(config);
    assertThat(code).isZero(); // 构建通过 + 评审如实跳过 = 正常收尾
  }

  @Test
  void reviewRuntimeFailureStillKeepsBuildReport() {
    // 评审段非 CraException 故障（如非法 LLM 端点）：仍须渲染已完成的实跑报告，退出码 2
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--local",
              "--base",
              branch,
              "--workspace",
              workspace.toString(),
              "--run-root",
              runRoot.toString(),
              "--exec",
              "/bin/echo ok",
              "--ledger-db",
              "l.db"
            },
            Map.of(
                "CRA_LLM_API_KEY", "sk",
                "CRA_LLM_BASE_URL", "not-a-url",
                "CRA_LLM_MODEL", "glm"));
    assertThat(new LocalPrePrCommand().execute(config)).isEqualTo(2);
    try (var files = Files.list(runRoot)) {
      // 构建段先于评审段执行：日志存在即证明报告数据面未因评审装配失败而丢失
      assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("build-"))).isTrue();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void missingRepoDirectoryFailsAtBootstrap() {
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--local", "--base", "master", "--workspace", workspace.resolve("nope").toString()
            },
            Map.of());
    assertThatThrownBy(() -> new LocalPrePrCommand().execute(config))
        .isInstanceOf(CraIntegrationException.class);
  }
}
