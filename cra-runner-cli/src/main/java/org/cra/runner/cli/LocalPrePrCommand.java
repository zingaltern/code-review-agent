package org.cra.runner.cli;

import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraException;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.GitAssembly;
import org.cra.git.api.LocalGitAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * `cra review --local` 本地 pre-PR 模式（07 T2.10；05§3 本地形态）：
 *
 * <ol>
 *   <li><b>实跑构建/测试</b>：默认 {@code mvn -B -ntp verify}（--exec 可配），输出实时回显、退出码原样上报、
 *       失败摘录置顶——展示真实输出而非声称完成；
 *   <li><b>本地评审</b>：SQLite 账本（默认 ~/.cra/ledger.db）、默认 dry-run（05§3）；未配置 CRA_LLM_API_KEY 时
 *       评审段如实跳过（不伪装评审结果），评审范围 = base..HEAD 已提交变更；
 *   <li><b>退出码</b>：4 = 实跑构建/测试失败（决定性信号，优先于评审结论——先修构建再看建议）；其余沿用 05§3 （0 正常 / 2 自身故障 / 3
 *       security-block）。评审段故障在报告中如实声明。
 * </ol>
 */
final class LocalPrePrCommand {

  /** 实跑构建/测试失败（05§3 退出码 4，v1.3.2 定点修订）。 */
  static final int EXIT_BUILD_FAILED = 4;

  private static final Logger LOG = LoggerFactory.getLogger(LocalPrePrCommand.class);

  /**
   * 执行本地 pre-PR 流程。
   *
   * @param rawConfig 解析配置（本地身份/区间尚未解析）
   * @return 进程退出码
   */
  int execute(CraConfig rawConfig) {
    Path workspace = Path.of(rawConfig.workspace()).toAbsolutePath().normalize();
    try (LocalGitAdapter git = GitAssembly.createLocalAdapter(workspace)) {
      String repoIdentity = resolveIdentity(rawConfig, git, workspace);
      String[] ownerName = repoIdentity.split("/", 2);
      String baseSha = git.resolve(rawConfig.baseSha());
      String headSha =
          rawConfig.headSha() == null ? git.headSha() : git.resolve(rawConfig.headSha());
      CraConfig config = rawConfig.withResolvedLocal(ownerName[0], ownerName[1], baseSha, headSha);
      LOG.info(
          "cra pre-PR start: repo={} base={}@{} head={} depth={}",
          repoIdentity,
          rawConfig.baseSha(),
          shortSha(baseSha),
          shortSha(headSha),
          config.depth());

      BuildVerifyRunner.ExecResult exec =
          new BuildVerifyRunner(Path.of(config.runRoot()).toAbsolutePath())
              .run(workspace, config.execCommand());

      ReviewResult review = null;
      String reviewSkipReason = null;
      String reviewError = null;
      if (config.llmApiKey() == null || config.llmApiKey().isBlank()) {
        reviewSkipReason = "未配置 CRA_LLM_API_KEY（密钥只从环境变量读，05§6）——LLM 评审未运行，本报告仅含构建/测试实跑结果";
      } else {
        try {
          review = runReview(config, git);
        } catch (CraException e) {
          // 评审段自身故障（配置/账本/网关）：不掩盖实跑结论，报告中如实声明
          LOG.error("本地评审段失败: {}", e.getMessage());
          reviewError = e.getMessage();
        }
      }

      System.out.println(
          LocalPrePrReport.render(
              config,
              exec,
              repoIdentity,
              rawConfig.baseSha(),
              review,
              reviewSkipReason,
              reviewError));

      if (exec.failed()) {
        return EXIT_BUILD_FAILED;
      }
      if (reviewError != null) {
        return 2;
      }
      if (review != null) {
        if (review.status() == RunStatus.FAILED) {
          return 2; // 自身故障（02§4.2），构建已通过
        }
        if (config.securityBlock() && review.stats().securityPublished() > 0) {
          return 3;
        }
      }
      return 0;
    }
  }

  /** 仓库身份：显式 --repo 优先，否则 remote origin 推导，兜底 local/&lt;目录名&gt;（账本键控用，诚实降级）。 */
  private static String resolveIdentity(CraConfig config, LocalGitAdapter git, Path workspace) {
    if (config.repoOwner() != null) {
      return config.repoOwner() + "/" + config.repoName();
    }
    return git.remoteOriginOwnerSlashName().orElseGet(() -> "local/" + workspace.getFileName());
  }

  private ReviewResult runReview(CraConfig config, LocalGitAdapter git) {
    if (config.llmBaseUrl() == null
        || config.llmBaseUrl().isBlank()
        || config.llmModel() == null
        || config.llmModel().isBlank()) {
      throw new CraConfigException("已配置 CRA_LLM_API_KEY 但 CRA_LLM_BASE_URL/CRA_LLM_MODEL 缺失");
    }
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(CraConfig.class, () -> config);
      context.registerBean(LocalGitAdapter.class, () -> git);
      context.register(LocalAppConfig.class);
      context.refresh();
      Orchestrator orchestrator = context.getBean(Orchestrator.class);
      ReviewRequest request =
          new ReviewRequest(
              config.repoOwner(),
              config.repoName(),
              config.prNumber(),
              config.baseSha(),
              config.headSha(),
              ReviewCommand.toDepth(config.depth()),
              config.dryRun(),
              config.securityBlock());
      return orchestrator.run(request);
    }
  }

  private static String shortSha(String sha) {
    return sha.substring(0, Math.min(7, sha.length()));
  }
}
