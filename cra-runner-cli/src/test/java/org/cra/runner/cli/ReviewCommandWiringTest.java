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
    CraConfig config =
        new CraConfig(
            "o",
            "r",
            1,
            "a",
            "b",
            "standard",
            true,
            false,
            "token",
            "http://127.0.0.1:1",
            "sk",
            "http://127.0.0.1:1",
            "m",
            runRoot.toString());
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
            new ReviewStats(0, 0, 0, 0, 0, 0, 0, List.of(), securityPublished),
            "",
            "");
    CraConfig config =
        new CraConfig(
            "o", "r", 1, "a", "b", "standard", false, true, "t", "api", "sk", "url", "m", ".");
    return ReviewCommand.exitCode(result, config);
  }
}
