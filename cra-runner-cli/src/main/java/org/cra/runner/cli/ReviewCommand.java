package org.cra.runner.cli;

import org.cra.common.exception.CraException;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.core.api.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * `cra review` 命令执行（05§3）：装配组合根 → run → 退出码映射。 退出码：0 正常完成（有 finding 不是失败；SKIPPED/SUCCESS/DEGRADED
 * 均为正常收尾）； 2 自身故障（配置非法 / run FAILED / 未预期异常）；3 --security-block 下存在 security finding。
 */
final class ReviewCommand {

  private static final Logger LOG = LoggerFactory.getLogger(ReviewCommand.class);

  /**
   * 执行评审命令（CI 形态；本地 pre-PR 模式经 --local 分发至 {@link LocalPrePrCommand}）。
   *
   * @param config 运行配置（CliArgs.parse 产物）
   * @return 进程退出码
   */
  int execute(CraConfig config) {
    if (config.local()) {
      return new LocalPrePrCommand().execute(config);
    }
    try {
      CraConfig effective =
          config.reviewMode() == org.cra.common.run.ReviewMode.COMMIT
              ? config.withResolvedPushBase(PushBaseResolver.resolve(config))
              : config;
      try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
        context.registerBean(CraConfig.class, () -> effective);
        context.register(AppConfig.class);
        context.refresh();
        Orchestrator orchestrator = context.getBean(Orchestrator.class);
        ReviewRequest request =
            new ReviewRequest(
                effective.repoOwner(),
                effective.repoName(),
                effective.prNumber(),
                effective.baseSha(),
                effective.headSha(),
                toDepth(effective.depth()),
                effective.dryRun(),
                effective.securityBlock(),
                effective.signoff().context(),
                effective.reviewMode());
        LOG.info(
            "cra review start: repo={}/{} mode={} pr={} base={} head={}",
            effective.repoOwner(),
            effective.repoName(),
            effective.reviewMode(),
            effective.prNumber(),
            effective.baseSha(),
            effective.headSha());
        ReviewResult result = orchestrator.run(request);
        LOG.info("cra review end: {}", result.summaryLine());
        return exitCode(result, effective);
      }
    } catch (CraException e) {
      LOG.error("cra review config/bootstrap failure: {}", e.getMessage());
      return 2;
    } catch (RuntimeException e) {
      LOG.error("cra review unexpected failure", e);
      return 2;
    }
  }

  static int exitCode(ReviewResult result, CraConfig config) {
    if (result.status() == RunStatus.FAILED) {
      return 2; // 自身故障（02§4.2：非零退出但不阻塞 PR）
    }
    if (config.securityBlock() && result.stats().securityPublished() > 0) {
      return 3; // --security-block 模式（05§3）
    }
    return 0; // 有 finding 不是失败（05§3）
  }

  static org.cra.common.run.Depth toDepth(String depth) {
    return switch (depth) {
      case "quick" -> org.cra.common.run.Depth.QUICK;
      case "deep" -> org.cra.common.run.Depth.DEEP;
      default -> org.cra.common.run.Depth.STANDARD;
    };
  }
}
