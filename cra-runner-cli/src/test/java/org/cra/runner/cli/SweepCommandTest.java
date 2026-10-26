package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** `cra sweep` 的范围/截断声明与退出码（M4d，05§2.5）。 */
class SweepCommandTest {

  @Test
  void scopeDeclarationReportsWindowSelectedAndTruncated() {
    String line = SweepCommand.scopeDeclaration("30d", 12, 10, 10);
    assertThat(line)
        .contains("since=30d")
        .contains("窗口内 12 个 PR")
        .contains("本次评审前 10 个")
        .contains("截断 2 个");
  }

  @Test
  void scopeDeclarationReportsZeroTruncationWhenWindowFits() {
    assertThat(SweepCommand.scopeDeclaration("7d", 2, 2, 10)).contains("截断 0 个");
  }

  @Test
  void exitCodeReflectsPerPrAndRollupFailures() {
    assertThat(SweepCommand.exitCodeFor(0, true)).isZero();
    assertThat(SweepCommand.exitCodeFor(1, true)).isEqualTo(2);
    assertThat(SweepCommand.exitCodeFor(0, false)).isEqualTo(2);
  }
}
