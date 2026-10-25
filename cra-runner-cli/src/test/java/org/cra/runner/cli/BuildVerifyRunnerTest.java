package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraIntegrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** pre-PR 实跑执行器（07 T2.10）：真实子进程、退出码原样上报、日志落盘、失败摘录提取。 */
class BuildVerifyRunnerTest {

  @TempDir private Path tempDir;

  @Test
  void runsRealProcessAndPersistsLog() {
    BuildVerifyRunner runner = new BuildVerifyRunner(tempDir.resolve("runs"));
    var result = runner.run(tempDir, "/bin/echo hello cra");
    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("hello cra");
    assertThat(result.logFile()).exists();
    assertThat(result.logFile())
        .content()
        .contains("command: /bin/echo hello cra")
        .contains("exit code: 0");
  }

  @Test
  void reportsNonZeroExitVerbatim() {
    BuildVerifyRunner runner = new BuildVerifyRunner(tempDir);
    var result = runner.run(tempDir, "/usr/bin/false");
    // 退出码原样上报——1 就是 1，不翻译不掩盖（T2.10 纪律）
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.failed()).isTrue();
  }

  @Test
  void missingBinaryFailsWithIntegrationException() {
    BuildVerifyRunner runner = new BuildVerifyRunner(tempDir);
    assertThatThrownBy(() -> runner.run(tempDir, "/no/such/binary --arg"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("实跑命令启动失败");
  }

  @Test
  void extractsMavenErrorLinesAsExcerpts() {
    String output =
        String.join(
            "\n",
            "[INFO] Scanning for projects...",
            "[INFO] Building cra 1.0",
            "[ERROR] Failures: ",
            "[ERROR]   DemoTest.badCase:12 expected: <true> but was: <false>",
            "[ERROR]   DemoTest.anotherCase:20 1 expectation failed.",
            "[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0",
            "[INFO] ------------------------------------------------------------------------",
            "[ERROR] BUILD FAILURE",
            "[INFO] Total time:  01.234 s");
    List<String> excerpts = BuildVerifyRunner.failureExcerpts(output);
    assertThat(excerpts).hasSize(5).allSatisfy(l -> assertThat(l).startsWith("[ERROR]"));
    assertThat(excerpts).anyMatch(l -> l.contains("BUILD FAILURE"));
    assertThat(BuildVerifyRunner.excerptTruncated(output)).isFalse();
  }

  @Test
  void nonMavenFailureFallsBackToTailLines() {
    String output = String.join("\n", "step 1 ok", "step 2 ok", "boom: exit 1", "", "");
    List<String> excerpts = BuildVerifyRunner.failureExcerpts(output);
    assertThat(excerpts).containsExactly("step 1 ok", "step 2 ok", "boom: exit 1");
  }

  @Test
  void truncatesHugeErrorListsAndFlags() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      sb.append("[ERROR] line ").append(i).append('\n');
    }
    String output = sb.toString();
    List<String> excerpts = BuildVerifyRunner.failureExcerpts(output);
    assertThat(excerpts).hasSize(BuildVerifyRunner.EXCERPT_CAP);
    assertThat(BuildVerifyRunner.excerptTruncated(output)).isTrue();
  }
}
