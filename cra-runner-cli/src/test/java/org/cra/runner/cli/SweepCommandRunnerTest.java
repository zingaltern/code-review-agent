package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** `cra sweep` 的范围/截断/预算声明与退出码（M4d，05§2.5）。 */
class SweepCommandRunnerTest {

  @Test
  void scopeDeclarationReportsWindowSelectedAndTruncated() {
    String line = SweepCommandRunner.scopeDeclaration("30d", 12, 10, 10);
    assertThat(line)
        .contains("since=30d")
        .contains("窗口内 12 个 PR")
        .contains("本次评审前 10 个")
        .contains("截断 2 个");
  }

  @Test
  void scopeDeclarationReportsZeroTruncationWhenWindowFits() {
    assertThat(SweepCommandRunner.scopeDeclaration("7d", 2, 2, 10)).contains("截断 0 个");
  }

  @Test
  void budgetDeclarationUsesActualConfiguredBudget() {
    // 汇总正文的预算声明必须取本次运行实际配置（不得写死 500k）
    String line = SweepCommandRunner.budgetDeclaration(800_000L, 2, List.of("degraded_budget"));
    assertThat(line)
        .contains("run-total-tokens=800000")
        .contains("截断抑制 2 条")
        .contains("degraded_budget");
    assertThat(SweepCommandRunner.budgetDeclaration(500_000L, 0, List.of()))
        .contains("run-total-tokens=500000")
        .contains("降级事件 无");
  }

  @Test
  void exitCodeReflectsPerPrAndRollupFailures() {
    assertThat(SweepCommandRunner.exitCodeFor(0, true)).isZero();
    assertThat(SweepCommandRunner.exitCodeFor(1, true)).isEqualTo(2);
    assertThat(SweepCommandRunner.exitCodeFor(0, false)).isEqualTo(2);
  }
}
