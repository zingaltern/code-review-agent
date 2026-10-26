package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.ReviewSuggestion;
import org.cra.common.run.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地 pre-PR 报告渲染（07 T2.10）：失败摘录置顶、建议清单、如实声明（跳过/故障/空建议）。 */
class LocalPrePrReportTest {

  @TempDir private Path tempDir;

  private static CraConfig config(boolean local, boolean dryRun) {
    return new CraConfig(
        "zingaltern",
        "cra",
        0,
        "abc1234000000000000000000000000000000000",
        "def5678000000000000000000000000000000000",
        "standard",
        dryRun,
        false,
        null,
        "https://api.github.com",
        null,
        null,
        null,
        ".cra/runs",
        ".",
        2000,
        8000,
        3,
        500_000L,
        0.70,
        0.85,
        "never",
        20,
        local,
        "mvn -B -ntp verify",
        "/tmp/ledger.db",
        null,
        null,
        "standard,light",
        "");
  }

  private static BuildVerifyRunner.ExecResult exec(int code, String output) {
    return new BuildVerifyRunner.ExecResult(code, output, Path.of("/tmp/build.log"), 1500);
  }

  @Test
  void failedBuildShowsExitCodeAndExcerptsOnTop() {
    String mvnOutput =
        "[INFO] BUILD cra\n"
            + "[ERROR] Failures: \n"
            + "[ERROR]   DemoTest.badCase:12 expected: <true> but was: <false>\n"
            + "[ERROR] BUILD FAILURE\n";
    String report =
        LocalPrePrReport.render(
            config(true, true),
            exec(1, mvnOutput),
            "zingaltern/cra",
            "main",
            null,
            "未配置 CRA_LLM_API_KEY——LLM 评审未运行",
            null);
    assertThat(report)
        .contains("【1】构建/测试实跑")
        .contains("退出码: 1（非零——构建/测试失败）")
        .contains("失败摘录（真实输出节选，置顶）")
        .contains("DemoTest.badCase:12")
        .contains("[ERROR] BUILD FAILURE")
        .contains("已跳过——未配置 CRA_LLM_API_KEY")
        .contains("仅已提交变更");
  }

  @Test
  void passingBuildWithSuggestionsAndTemplateTrace() {
    ReviewResult review =
        new ReviewResult(
            "run_1",
            "zingaltern",
            "cra",
            0,
            "def5678",
            RunStatus.SUCCESS,
            new ReviewStats(
                2,
                2,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                100,
                20,
                900,
                List.of(),
                0,
                new org.cra.common.run.Coverage(2, 2, "全量")),
            "file://.cra/runs/run_1",
            "run=run_1 status=SUCCESS",
            List.of(
                new ReviewSuggestion(
                    "IMPORTANT", "空列表越界", "src/ListUtil.java", 13, "size()-1 返回 -1", "先判空再取尾元素")));
    String report =
        LocalPrePrReport.render(
            config(true, false),
            exec(0, "[INFO] BUILD SUCCESS"),
            "zingaltern/cra",
            "main",
            review,
            null,
            null);
    assertThat(report)
        .contains("退出码: 0（通过）")
        .contains("状态: SUCCESS")
        .contains("建议清单（1 条）")
        .contains("[IMPORTANT] 空列表越界 — `src/ListUtil.java:13`")
        .contains("建议: 先判空再取尾元素")
        .contains("dry-run=false")
        .contains("/tmp/ledger.db")
        .contains("file://.cra/runs/run_1");
  }

  @Test
  void emptySuggestionsDeclaredHonestly() {
    ReviewResult review =
        new ReviewResult(
            "run_2",
            "zingaltern",
            "cra",
            0,
            "def5678",
            RunStatus.SUCCESS,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                10,
                List.of(),
                0,
                new org.cra.common.run.Coverage(0, 0, "")),
            "file://.cra/runs/run_2",
            "run=run_2",
            List.of());
    String report =
        LocalPrePrReport.render(
            config(true, true),
            exec(0, "[INFO] BUILD SUCCESS"),
            "zingaltern/cra",
            "main",
            review,
            null,
            null);
    assertThat(report).contains("建议清单: 无（本次未发现新问题）");
  }

  @Test
  void reviewErrorDeclaredSeparately() {
    String report =
        LocalPrePrReport.render(
            config(true, true),
            exec(1, "[ERROR] x"),
            "zingaltern/cra",
            "main",
            null,
            null,
            "SQLite 账本连接失败");
    assertThat(report).contains("状态: 评审故障——SQLite 账本连接失败").contains("退出码: 1（非零——构建/测试失败）");
  }

  @Test
  void signoffAdviceRenderedAsAdvisoryWithCommitBinding() {
    // 07§5 M4a：本地报告同样输出风险建议 + commit 绑定 + 非审批声明
    org.cra.common.signoff.SignoffAdvice advice =
        org.cra.common.signoff.SignoffAdvice.advisory(
            org.cra.common.signoff.RiskTier.HIGH,
            "def5678000000000000000000000000000000000",
            new org.cra.common.signoff.SignoffContext(
                "zingaltern", org.cra.common.signoff.TestsStatus.PASSED),
            List.of(
                new org.cra.common.signoff.SignoffRuleHit(
                    "high-risk-path",
                    org.cra.common.signoff.RiskTier.HIGH,
                    "命中核心高危路径（安全/支付/鉴权区域）",
                    List.of("src/main/java/com/acme/auth/AuthService.java"))));
    ReviewResult review =
        new ReviewResult(
            "run_3",
            "zingaltern",
            "cra",
            0,
            "def5678000000000000000000000000000000000",
            RunStatus.SUCCESS,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                10,
                List.of(),
                0,
                new org.cra.common.run.Coverage(1, 1, "全量")),
            "file://.cra/runs/run_3",
            "run=run_3",
            List.of(),
            advice);
    String report =
        LocalPrePrReport.render(
            config(true, false),
            exec(0, "[INFO] BUILD SUCCESS"),
            "zingaltern/cra",
            "main",
            review,
            null,
            null);
    assertThat(report)
        .contains("签字建议（advisory，非审批）: 高风险 → 建议 2 人签字")
        .contains("绑定 commit def5678")
        .contains("high-risk-path")
        .contains("agent 永远不是 approver")
        .contains("核实意图/假设/设计")
        .contains("指挥 agent 写代码的人必须在签字名单上");
  }

  @Test
  void disabledSignoffDeclaredHonestly() {
    ReviewResult review =
        new ReviewResult(
            "run_4",
            "zingaltern",
            "cra",
            0,
            "def5678000000000000000000000000000000000",
            RunStatus.SUCCESS,
            new ReviewStats(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                10,
                List.of(),
                0,
                new org.cra.common.run.Coverage(0, 0, "")),
            "file://.cra/runs/run_4",
            "run=run_4",
            List.of(),
            org.cra.common.signoff.SignoffAdvice.disabled(
                "def5678000000000000000000000000000000000",
                org.cra.common.signoff.SignoffContext.unknown()));
    String report =
        LocalPrePrReport.render(
            config(true, false),
            exec(0, "[INFO] BUILD SUCCESS"),
            "zingaltern/cra",
            "main",
            review,
            null,
            null);
    assertThat(report).contains("签字建议: 已关闭（agents.signoff.enabled=false）");
  }
}
