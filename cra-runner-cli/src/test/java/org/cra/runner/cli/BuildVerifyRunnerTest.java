package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
  void streamsCompleteOutputToLogWhileMemoryStaysTruncated() throws Exception {
    BuildVerifyRunner runner = new BuildVerifyRunner(tempDir.resolve("runs"), 200);
    var result = runner.run(tempDir, "/usr/bin/seq 1 1000");
    assertThat(result.exitCode()).isZero();
    assertThat(result.output())
        .contains("内存留存已截断")
        .contains("完整输出仍见日志文件")
        .doesNotContain("\n1000\n");
    String log = Files.readString(result.logFile());
    assertThat(log)
        .contains("command: /usr/bin/seq 1 1000")
        .contains("\n1000\nexit code: 0")
        .hasSizeGreaterThan(3_000);
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
    assertThat(excerpts)
        .hasSize(5)
        .allSatisfy(l -> assertThat(l).startsWith("[ERROR]"))
        .anyMatch(l -> l.contains("BUILD FAILURE"));
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

  @Test
  void tailExcerptCountsNonBlankLinesWithoutTrailingNewlineOffByOne() {
    // 生产输出总以换行结尾：恰好 20 行时不得误报截断/漏首行；21 行时取末尾 20 行
    String exactlyTwenty = numberedLines(20);
    assertThat(BuildVerifyRunner.failureExcerpts(exactlyTwenty)).hasSize(20).startsWith("line 1");
    assertThat(BuildVerifyRunner.excerptTruncated(exactlyTwenty)).isFalse();

    String twentyOne = numberedLines(21);
    List<String> excerpts = BuildVerifyRunner.failureExcerpts(twentyOne);
    assertThat(excerpts).hasSize(20).startsWith("line 2").endsWith("line 21");
    assertThat(BuildVerifyRunner.excerptTruncated(twentyOne)).isTrue();
  }

  @Test
  void timeoutKillsDescendantProcesses() throws Exception {
    // Maven 会 fork 测试 JVM：超时必须清理整棵进程树，不能只杀父进程留下孤儿
    Path script = tempDir.resolve("slow.sh");
    Files.writeString(script, "#!/bin/sh\nsleep 30 &\necho $! > child.pid\nwait\n");
    Path childPidFile = tempDir.resolve("child.pid");
    System.setProperty("cra.verify.timeoutSec", "1");
    try {
      BuildVerifyRunner runner = new BuildVerifyRunner(tempDir.resolve("runs"));
      assertThatThrownBy(() -> runner.run(tempDir, "/bin/sh " + script))
          .isInstanceOf(CraIntegrationException.class)
          .hasMessageContaining("超时");
    } finally {
      System.clearProperty("cra.verify.timeoutSec");
    }
    long childPid = Long.parseLong(Files.readString(childPidFile).strip());
    // 进程已不存在同样视为已终止；仍在则等待其退出信号（上限 5s，不再轮询 sleep）
    boolean terminated =
        ProcessHandle.of(childPid)
            .map(ph -> ph.onExit().completeOnTimeout(null, 5, TimeUnit.SECONDS).join() != null)
            .orElse(true);
    assertThat(terminated).as("超时后子进程应已被终止（含进程树）").isTrue();
  }

  private static String numberedLines(int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      out.append("line ").append(i).append('\n');
    }
    return out.toString();
  }
}
