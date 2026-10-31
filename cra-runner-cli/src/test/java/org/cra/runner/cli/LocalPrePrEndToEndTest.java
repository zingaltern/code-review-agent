package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验收 8 e2e（07§3 验收 8 / 08 场景 16，@Tag("slow") 本地按需，不进常规 CI 时长预算）： `cra review --local`
 * 在注入了会挂测试的分支上，报告<b>如实展示测试失败输出</b>且<b>退出码非零</b>（4）； 正常分支输出建议清单——pre-PR 不是空壳的直接证据。
 *
 * <p>真实链路：真实 git 仓库（jgit 建 fixture）+ 真实 {@code mvn -B -ntp verify} 子进程（依赖取本机 ~/.m2 缓存，版本与主仓对齐保证命中）+
 * 真实 CLI 参数解析；LLM 段无密钥如实跳过（07§6.3：LLM 调用不进单测）。
 */
@Tag("slow")
class LocalPrePrEndToEndTest {

  private static final String FIXTURE_POM =
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>demo</groupId>
        <artifactId>cra-fixture</artifactId>
        <version>1.0.0</version>
        <packaging>jar</packaging>
        <properties>
          <maven.compiler.release>17</maven.compiler.release>
          <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>
        <dependencies>
          <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.12.2</version>
            <scope>test</scope>
          </dependency>
        </dependencies>
        <build>
          <plugins>
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-surefire-plugin</artifactId>
              <version>3.5.4</version>
            </plugin>
          </plugins>
        </build>
      </project>
      """;

  private static final String PASSING_TEST =
      """
      package demo;
      import static org.junit.jupiter.api.Assertions.assertTrue;
      import org.junit.jupiter.api.Test;
      public class DemoTest {
        @Test
        void works() { assertTrue(true); }
      }
      """;

  private static final String FAILING_TEST =
      """
      package demo;
      import static org.junit.jupiter.api.Assertions.assertTrue;
      import org.junit.jupiter.api.Test;
      public class DemoTest {
        @Test
        void works() { assertTrue(false, "deliberate acceptance-8 failure"); }
      }
      """;

  @TempDir private Path repoDir;

  @TempDir private Path scratch;

  private Git git;
  private String defaultBranch;

  @BeforeEach
  void setUpFixtureRepo() throws Exception {
    git = Git.init().setDirectory(repoDir.toFile()).call();
    git.getRepository().getConfig().setString("user", null, "name", "tester");
    git.getRepository().getConfig().setString("user", null, "email", "t@t");
    defaultBranch = git.getRepository().getBranch();
    writeAndCommit("pom.xml", FIXTURE_POM, "init: passing fixture");
    writeAndCommit("src/test/java/demo/DemoTest.java", PASSING_TEST, "init: passing test");
  }

  @AfterEach
  void tearDown() {
    if (git != null) {
      git.close();
    }
  }

  private void writeAndCommit(String path, String content, String message) throws Exception {
    Path file = repoDir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    git.add().addFilepattern(".").call();
    git.commit().setMessage(message).call();
  }

  /** 注入会挂测试的分支（验收 8 的注入动作）。 */
  private void injectFailingBranch() throws Exception {
    git.branchCreate().setName("cra-bad").call();
    git.checkout().setName("cra-bad").call();
    writeAndCommit("src/test/java/demo/DemoTest.java", FAILING_TEST, "inject: broken test");
    git.checkout().setName(defaultBranch).call();
  }

  private String[] args(String... extra) {
    String[] head =
        new String[] {
          "--local",
          "--base",
          defaultBranch,
          "--workspace",
          repoDir.toString(),
          "--run-root",
          scratch.resolve("runs").toString(),
          "--ledger-db",
          scratch.resolve("ledger.db").toString(),
          "--exec",
          "mvn -B -ntp verify"
        };
    String[] all = new String[head.length + extra.length];
    System.arraycopy(head, 0, all, 0, head.length);
    System.arraycopy(extra, 0, all, head.length, extra.length);
    return all;
  }

  /** 走与 Main.run 相同的入口（密钥环境固定为空，不依赖宿主机 env）。 */
  private Executed execute(String... extra) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    PrintStream original = System.out;
    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
    int code;
    try {
      CraConfig config = CliArgs.parse(args(extra), Map.of());
      code = new ReviewCommandRunner().execute(config);
    } finally {
      System.setOut(original);
    }
    return new Executed(code, stdout.toString(StandardCharsets.UTF_8));
  }

  private record Executed(int exitCode, String stdout) {}

  @Test
  @Timeout(600)
  void failingBranchShowsRealTestFailureOutputAndExitsNonZero() throws Exception {
    injectFailingBranch();
    git.checkout().setName("cra-bad").call();

    Executed executed = execute();

    // 退出码非零（4 = 实跑构建/测试失败，05§3 定点修订）
    assertThat(executed.exitCode())
        .as("验收 8：注入挂测试的分支必须以非零码退出")
        .isEqualTo(LocalPrePrCommandRunner.EXIT_BUILD_FAILED);
    // 报告如实展示真实测试失败输出（摘录置顶 + 真实 [ERROR] 行 + 原始退出码）
    assertThat(executed.stdout())
        .contains("【1】构建/测试实跑")
        .contains("命令: mvn -B -ntp verify")
        .contains("退出码: 1（非零——构建/测试失败）")
        .contains("失败摘录（真实输出节选，置顶）")
        .contains("[ERROR]")
        .contains("DemoTest");
    assertThat(executed.stdout())
        .as("失败摘录须含 surefire 的失败断言原文")
        .containsPattern("(?s)deliberate acceptance-8 failure|Failures: \\d");
    // 评审段如实声明（无密钥跳过，不伪装评审结果）
    assertThat(executed.stdout()).contains("已跳过——未配置 CRA_LLM_API_KEY");
    // 实跑日志落盘留证
    try (var files = Files.list(scratch.resolve("runs"))) {
      assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("build-"))).isTrue();
    }
  }

  @Test
  @Timeout(600)
  void passingBranchPrintsSuggestionSectionAndExitsZero() {
    Executed executed = execute();

    assertThat(executed.exitCode()).as("验收 8：正常分支正常收尾（有/无建议都不是失败）").isZero();
    assertThat(executed.stdout())
        .contains("退出码: 0（通过）")
        .contains("【2】本地评审")
        .contains("建议清单: 未生成（评审未运行）")
        .contains("已跳过——未配置 CRA_LLM_API_KEY")
        .contains("dry-run=true");
  }
}
