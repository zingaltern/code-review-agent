package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 组合根装配与退出码映射（05§3；Spring 装配错误在自举首跑即暴露——08§2.3 不做全量上下文测试， 这里只做轻量装配 smoke + 退出码纯逻辑）。 */
class ReviewCommandWiringTest {

  @TempDir Path runRoot;

  @Test
  void compositionRootAssemblesFullGraph() {
    CraConfig config = config(runRoot.toString()).dryRun(true).build();
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean(CraConfig.class, () -> config);
      context.register(AppConfig.class);
      context.refresh();
      assertThat(context.getBean(org.cra.core.api.Orchestrator.class)).isNotNull();
      assertThat(context.getBean(org.cra.git.api.GitPlatformAdapter.class)).isNotNull();
      assertThat(context.getBean(org.cra.store.api.ReviewLedger.class)).isNotNull();
      assertThat(context.getBean(org.cra.llm.api.LlmGateway.class)).isNotNull();
    }
  }

  /** 最小装配形态（CI 形态；LLM/Git 端点指向本机不可达端口——装配不发起网络调用）。 */
  private static CraConfig.Builder config(String runRoot) {
    return CraConfig.builder()
        .repoOwner("o")
        .repoName("r")
        .prNumber(1)
        .baseSha("a")
        .headSha("b")
        .depth("standard")
        .githubToken("token")
        .githubApi("http://127.0.0.1:1")
        .llmApiKey("sk")
        .llmBaseUrl("http://127.0.0.1:1")
        .llmModel("m")
        .runRoot(runRoot)
        .workspace(".")
        .largeDiffLines(2000)
        .hardCapLines(8000)
        .nitBudget(3)
        .runTotalTokens(500_000L)
        .degradeAtFirst(0.70)
        .degradeAtSecond(0.85)
        .publishUnverified("never")
        .spotCheckPercent(20)
        .execCommand("mvn -B -ntp verify")
        .ledgerDb("~/.cra/ledger.db")
        .fallbackChain("standard,light")
        .rpm("");
  }

  @Test
  void exitCodeMapping() {
    assertThat(exitFor(RunStatus.SUCCESS, 0)).isZero();
    assertThat(exitFor(RunStatus.DEGRADED, 0)).isZero(); // 降级完成是正常收尾
    assertThat(exitFor(RunStatus.SKIPPED, 0)).isZero();
    assertThat(exitFor(RunStatus.FAILED, 0)).isEqualTo(2);
    assertThat(exitFor(RunStatus.SUCCESS, 2)).isEqualTo(3); // security finding + block
    assertThat(exitFor(RunStatus.SUCCESS, 0)).isZero(); // block 但无 security finding
  }

  private static int exitFor(RunStatus status, int securityPublished) {
    ReviewResult result =
        new ReviewResult(
            "run_1",
            "o",
            "r",
            1,
            "h",
            status,
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
                0,
                List.of(),
                securityPublished,
                new org.cra.common.run.Coverage(0, 0, "")),
            "",
            "");
    CraConfig config = config(".").securityBlock(true).build();
    return ReviewCommand.exitCode(result, config);
  }
}
