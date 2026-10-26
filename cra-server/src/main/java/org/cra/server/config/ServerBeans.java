package org.cra.server.config;

import java.util.Set;
import javax.sql.DataSource;
import org.cra.agents.api.AgentsAssembly;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.PipelineOptions;
import org.cra.git.api.GitAssembly;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;
import org.cra.llm.api.LlmAssembly;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmGatewayConfig;
import org.cra.llm.api.LlmGatewayConfig.ModelEndpoint;
import org.cra.server.attest.AttestationService;
import org.cra.server.attest.AttestationStore;
import org.cra.server.attest.KnownCommitChecker;
import org.cra.server.attest.PostgresAttestationStore;
import org.cra.server.attest.PostgresKnownCommitChecker;
import org.cra.server.delivery.DeliveryStore;
import org.cra.server.delivery.PostgresDeliveryStore;
import org.cra.server.history.EventHistoryCollectionJob;
import org.cra.server.history.EventHistoryCollector;
import org.cra.server.history.NoopEventHistoryCollector;
import org.cra.server.pipeline.CoreReviewExecutor;
import org.cra.server.pipeline.ReviewExecutor;
import org.cra.server.pipeline.ReviewJobHandler;
import org.cra.server.queue.ReviewQueue;
import org.cra.server.store.ServerSchemaMigrator;
import org.cra.server.web.ApiTokenFilter;
import org.cra.server.webhook.WebhookSignatureVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * cra-server 组合根（10§3/10§4：第二组合根；Spring 仅在此模块——R6 扩展集，ADR-0008）。
 *
 * <p>所有对象图经各模块 api 装配工厂创建；cra-core 只面向接口编程。PG 账本按仓库在 {@link CoreReviewExecutor} 内创建，本类只提供
 * DataSource 与一次性 schema 迁移。
 */
@Configuration
@EnableScheduling
public class ServerBeans {

  /**
   * 服务配置（环境变量绑定 + 硬校验）。
   *
   * @param env Spring Environment
   * @return 配置
   */
  @Bean
  public ServerConfig serverConfig(Environment env) {
    return ServerConfig.from(env);
  }

  /**
   * GitHub 组件（adapter + CommentSource）。
   *
   * @param config 服务配置
   * @return GitHubComponents
   */
  @Bean
  public GitHubComponents gitHubComponents(ServerConfig config) {
    return GitHubAssembly.createComponents(config.githubApiBase(), config.githubToken());
  }

  /**
   * Git 平台适配器。
   *
   * @param components GitHub 组件
   * @return adapter
   */
  @Bean
  public GitPlatformAdapter gitPlatformAdapter(GitHubComponents components) {
    return components.adapter();
  }

  /**
   * LLM 网关（三档路由；缺失配置即拒绝启动）。
   *
   * @param config 服务配置
   * @return LlmGateway
   */
  @Bean
  public LlmGateway llmGateway(ServerConfig config) {
    if (config.llmApiKey().isBlank()
        || config.llmBaseUrl().isBlank()
        || config.llmModel().isBlank()) {
      throw new CraConfigException(
          "CRA_LLM_API_KEY / CRA_LLM_BASE_URL / CRA_LLM_MODEL 未配置（服务端评审需要 LLM）");
    }
    ModelEndpoint standard =
        new ModelEndpoint(config.llmModel(), config.llmBaseUrl(), config.llmApiKey());
    ModelEndpoint strong =
        new ModelEndpoint(
            modelOrInherit(config.llmModelStrong(), config.llmModel()),
            config.llmBaseUrl(),
            config.llmApiKey());
    ModelEndpoint light =
        new ModelEndpoint(
            modelOrInherit(config.llmModelLight(), config.llmModel()),
            config.llmBaseUrl(),
            config.llmApiKey());
    return LlmAssembly.createGateway(
        new LlmGatewayConfig(
            LlmGatewayConfig.models(standard, strong, light),
            config.parsedFallbackChain(),
            config.parsedRpm(LlmGatewayConfig.defaultRpm()),
            LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY,
            LlmGatewayConfig.DEFAULT_MAX_RETRIES,
            30_000,
            120_000,
            1_000,
            8_000,
            config.parsedTemperature(),
            extraHeadersFor(config.llmBaseUrl()),
            reasoningEffortFor(config.llmBaseUrl())));
  }

  /**
   * 附加请求头（v1.3.2 opencode-go 适配）：与 CLI 同语义——base-url 指向 opencode 时自动附加 {@code
   * x-opencode-session}（缺失时 opencode-go 返回 MissingSessionID）。
   *
   * @param baseUrl LLM 端点根地址
   * @return 附加头（非 opencode 端点为空 map）
   */
  private static java.util.Map<String, String> extraHeadersFor(String baseUrl) {
    if (baseUrl != null && baseUrl.contains("opencode.ai")) {
      return java.util.Map.of("x-opencode-session", java.util.UUID.randomUUID().toString());
    }
    return java.util.Map.of();
  }

  /**
   * 推理力度（v1.3.2 opencode 适配）：与 CLI 同语义——opencode 端点自动 none。reasoning token 计入 max_tokens，评审任务的 1–2k
   * 输出预算会被推理吃光（M3 冒烟实测 budget_exceeded）。
   *
   * @param baseUrl LLM 端点根地址
   * @return "none" 或 null（不传）
   */
  private static String reasoningEffortFor(String baseUrl) {
    return baseUrl != null && baseUrl.contains("opencode.ai") ? "none" : null;
  }

  /**
   * 评审班组（与 CLI 同一装配）。
   *
   * @return ReviewCrew
   */
  @Bean
  public ReviewCrew reviewCrew() {
    return AgentsAssembly.createReviewCrew();
  }

  /**
   * 验证子代理。
   *
   * @return verifier
   */
  @Bean
  public SubAgent<VerificationBatchInput> verifier() {
    return AgentsAssembly.createVerifier();
  }

  /**
   * 仲裁子代理。
   *
   * @return judge
   */
  @Bean
  public SubAgent<JudgeCaseInput> judge() {
    return AgentsAssembly.createJudge();
  }

  /**
   * 本地仓库上下文（服务端无 checkout 时 localRepoRoot 为空 = 合法降级，05§2.1）。
   *
   * @param config 服务配置
   * @return LocalRepoContext
   */
  @Bean
  public LocalRepoContext localRepoContext(ServerConfig config) {
    return GitAssembly.createLocalRepoContext(config.workspace());
  }

  /**
   * 编排参数（05§5 默认值 + 服务端 run 预算）。
   *
   * @param config 服务配置
   * @return PipelineOptions
   */
  @Bean
  public PipelineOptions pipelineOptions(ServerConfig config) {
    return new PipelineOptions(
        config.runTotalTokens(),
        0.70,
        0.85,
        2000,
        8000,
        3,
        "never",
        PipelineOptions.DEFAULT_SPOTCHECK_PERCENT);
  }

  /**
   * CrossChecker 配置（默认关闭，05§5）。
   *
   * @param config 服务配置
   * @return CrossCheckerOptions
   */
  @Bean
  public CrossCheckerOptions crossCheckerOptions(ServerConfig config) {
    Set<CrossCheckerOptions.Mode> modes = config.parsedCrossCheckerModes();
    return new CrossCheckerOptions(
        config.crossCheckerEnabled(), modes, config.crossCheckerHistoryWeeks());
  }

  /**
   * schema 迁移（启动时执行，失败即拒绝启动）。
   *
   * @param dataSource PG 连接池
   * @return migrator
   */
  @Bean(initMethod = "migrate")
  public ServerSchemaMigrator serverSchemaMigrator(DataSource dataSource) {
    return new ServerSchemaMigrator(dataSource);
  }

  /**
   * delivery 幂等登记。
   *
   * @param dataSource PG 连接池
   * @return DeliveryStore
   */
  @Bean
  public DeliveryStore deliveryStore(DataSource dataSource) {
    return new PostgresDeliveryStore(dataSource);
  }

  /**
   * 报告签发审计落库。
   *
   * @param dataSource PG 连接池
   * @return AttestationStore
   */
  @Bean
  public AttestationStore attestationStore(DataSource dataSource) {
    return new PostgresAttestationStore(dataSource);
  }

  /**
   * 已知 commit 检查（签发策略）。
   *
   * @param dataSource PG 连接池
   * @return KnownCommitChecker
   */
  @Bean
  public KnownCommitChecker knownCommitChecker(DataSource dataSource) {
    return new PostgresKnownCommitChecker(dataSource);
  }

  /**
   * 报告签发服务。
   *
   * @param config 服务配置
   * @param knownCommitChecker 已知 commit 检查
   * @param attestationStore 签发记录
   * @return AttestationService
   */
  @Bean
  public AttestationService attestationService(
      ServerConfig config,
      KnownCommitChecker knownCommitChecker,
      AttestationStore attestationStore) {
    return new AttestationService(
        config.signingKey(),
        config.signingKeyId(),
        config.attestRequireKnownCommit(),
        knownCommitChecker,
        attestationStore);
  }

  /**
   * 评审执行器（按仓库缓存 orchestrator）。
   *
   * @param dataSource PG 连接池
   * @param adapter Git 适配器
   * @param gateway LLM 网关
   * @param crew 班组
   * @param verifier 验证子代理
   * @param judge 仲裁子代理
   * @param localRepoContext 本地仓库上下文
   * @param pipelineOptions 编排参数
   * @param crossCheckerOptions CrossChecker 配置
   * @param config 服务配置
   * @return ReviewExecutor
   */
  @Bean
  public ReviewExecutor reviewExecutor(
      DataSource dataSource,
      GitPlatformAdapter adapter,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      LocalRepoContext localRepoContext,
      PipelineOptions pipelineOptions,
      CrossCheckerOptions crossCheckerOptions,
      ServerConfig config) {
    return new CoreReviewExecutor(
        dataSource,
        adapter,
        gateway,
        crew,
        verifier,
        judge,
        localRepoContext,
        pipelineOptions,
        crossCheckerOptions,
        config);
  }

  /**
   * 队列任务处理器。
   *
   * @param deliveryStore delivery 状态
   * @param executor 评审执行器
   * @return handler
   */
  @Bean
  public ReviewJobHandler reviewJobHandler(DeliveryStore deliveryStore, ReviewExecutor executor) {
    return new ReviewJobHandler(deliveryStore, executor);
  }

  /**
   * 有界队列（init 启动 worker，destroy 优雅排水）。
   *
   * @param config 服务配置
   * @param handler 任务处理器
   * @return ReviewQueue
   */
  @Bean(name = "reviewQueue", initMethod = "start", destroyMethod = "stop")
  public ReviewQueue reviewQueue(ServerConfig config, ReviewJobHandler handler) {
    return new ReviewQueue(
        config.queueCapacity(), config.workerCount(), config.drainTimeoutSeconds(), handler);
  }

  /**
   * webhook 签名校验器。
   *
   * @param config 服务配置
   * @return verifier
   */
  @Bean
  public WebhookSignatureVerifier webhookSignatureVerifier(ServerConfig config) {
    return new WebhookSignatureVerifier(config.webhookSecret());
  }

  /**
   * 事件史采集端口（M4b 占位实现）。
   *
   * @return collector
   */
  @Bean
  public EventHistoryCollector eventHistoryCollector() {
    return new NoopEventHistoryCollector();
  }

  /**
   * 事件史采集调度骨架。
   *
   * @param config 服务配置
   * @param collector 采集端口
   * @return job
   */
  @Bean
  public EventHistoryCollectionJob eventHistoryCollectionJob(
      ServerConfig config, EventHistoryCollector collector) {
    return new EventHistoryCollectionJob(config.eventHistoryEnabled(), collector);
  }

  /**
   * /api/v1/** 的 Bearer token 过滤器。
   *
   * @param config 服务配置
   * @return 注册项
   */
  @Bean
  public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(ServerConfig config) {
    FilterRegistrationBean<ApiTokenFilter> registration =
        new FilterRegistrationBean<>(new ApiTokenFilter(config.apiToken()));
    registration.addUrlPatterns("/api/v1/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  private static String modelOrInherit(String model, String standard) {
    return model == null || model.isBlank() ? standard : model;
  }
}
