package org.cra.server.pipeline;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.core.api.CoreAssembly;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.api.SignoffOptions;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.llm.api.LlmGateway;
import org.cra.server.config.ServerConfig;
import org.cra.server.queue.ReviewJob;
import org.cra.store.api.StoreAssembly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用 cra-core 执行评审（第二组合根，02§5）。账本按仓库创建 PostgreSQL 实例；Orchestrator 按仓库缓存——ReviewPipeline 持有固定 线程池，逐
 * job 新建会泄漏线程（09§6 池归属）。
 *
 * <p>无本地 checkout：LocalRepoContext 的 localRepoRoot 为空，pre-existing 检测按 05§2.1 降级为不标注 + Summary
 * 声明，不假装有能力。
 */
public final class CoreReviewExecutor implements ReviewExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(CoreReviewExecutor.class);

  private final DataSource dataSource;
  private final GitPlatformAdapter adapter;
  private final LlmGateway gateway;
  private final ReviewCrew crew;
  private final SubAgent<VerificationBatchInput> verifier;
  private final SubAgent<JudgeCaseInput> judge;
  private final LocalRepoContext localRepoContext;
  private final PipelineOptions pipelineOptions;
  private final CrossCheckerOptions crossCheckerOptions;
  private final EventHistoryQuery eventHistoryQuery;
  private final org.cra.core.api.SemanticContextOptions semanticContextOptions;
  private final org.cra.git.api.SymbolIndex symbolIndex;
  private final Path runRoot;
  private final Depth depth;
  private final boolean dryRun;
  private final Map<String, Orchestrator> orchestrators = new ConcurrentHashMap<>();

  /**
   * @param dataSource PG 连接池（账本）
   * @param adapter Git 平台适配器（GitHub 或 GitLab，ADR-0013）
   * @param gateway LLM 网关
   * @param crew 评审班组
   * @param verifier 验证子代理
   * @param judge 仲裁子代理
   * @param localRepoContext 本地仓库上下文（无 checkout 为空形态）
   * @param pipelineOptions 编排参数
   * @param crossCheckerOptions CrossChecker 配置
   * @param eventHistoryQuery 事件史查询端口（M4f-full；null = 未装配）
   * @param semanticContextOptions 语义上下文配置（M4e，ADR-0014；null = 关闭）
   * @param symbolIndex 语义索引端口（M4e；null = 未装配 → 开启时按不可得声明）
   * @param config 服务配置（深度/runRoot/dryRun）
   */
  public CoreReviewExecutor(
      DataSource dataSource,
      GitPlatformAdapter adapter,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      LocalRepoContext localRepoContext,
      PipelineOptions pipelineOptions,
      CrossCheckerOptions crossCheckerOptions,
      EventHistoryQuery eventHistoryQuery,
      org.cra.core.api.SemanticContextOptions semanticContextOptions,
      org.cra.git.api.SymbolIndex symbolIndex,
      ServerConfig config) {
    this.dataSource = dataSource;
    this.adapter = adapter;
    this.gateway = gateway;
    this.crew = crew;
    this.verifier = verifier;
    this.judge = judge;
    this.localRepoContext = localRepoContext;
    this.pipelineOptions = pipelineOptions;
    this.crossCheckerOptions = crossCheckerOptions;
    this.eventHistoryQuery = eventHistoryQuery;
    this.semanticContextOptions = semanticContextOptions;
    this.symbolIndex = symbolIndex;
    this.runRoot = config.runRoot();
    this.depth = Depth.valueOf(config.reviewDepth().toUpperCase(Locale.ROOT));
    this.dryRun = config.dryRun();
  }

  @Override
  public ReviewResult execute(ReviewJob job) {
    Orchestrator orchestrator =
        orchestrators.computeIfAbsent(job.repoKey(), key -> newOrchestrator(job));
    ReviewRequest request =
        new ReviewRequest(
            job.repoOwner(),
            job.repoName(),
            job.prNumber(),
            job.baseSha(),
            job.headSha(),
            depth,
            dryRun,
            false);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "review 开始: delivery={} repo={} pr={} head={} depth={}",
          job.deliveryId(),
          job.repoKey(),
          job.prNumber(),
          job.headSha(),
          depth.name().toLowerCase(Locale.ROOT));
    }
    ReviewResult result = orchestrator.run(request);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "review 结束: delivery={} repo={} pr={} run={} status={} findings={}",
          job.deliveryId(),
          job.repoKey(),
          job.prNumber(),
          result.runId(),
          result.status(),
          result.stats().findingsPublished());
    }
    return result;
  }

  /**
   * 已缓存的仓库数（诊断用）。
   *
   * @return 仓库数
   */
  public int cachedRepositories() {
    return orchestrators.size();
  }

  private Orchestrator newOrchestrator(ReviewJob job) {
    if (LOG.isInfoEnabled()) {
      LOG.info("为仓库创建 orchestrator: repo={}（PG 账本实例按仓库绑定）", job.repoKey());
    }
    return CoreAssembly.createOrchestrator(
        adapter,
        StoreAssembly.createPostgresLedger(job.repoOwner(), job.repoName(), dataSource),
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        pipelineOptions,
        localRepoContext,
        crossCheckerOptions,
        SignoffOptions.disabled(),
        eventHistoryQuery,
        semanticContextOptions,
        symbolIndex);
  }
}
