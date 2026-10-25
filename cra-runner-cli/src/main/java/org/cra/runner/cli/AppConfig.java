package org.cra.runner.cli;

import org.cra.agents.api.AgentsAssembly;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.SubAgent;
import org.cra.core.api.CoreAssembly;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;
import org.cra.llm.api.LlmAssembly;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组合根（10§3/10§4：M0–M3 全部对象图在 cra-runner-cli 装配；Spring 仅 DI/配置，@Autowired 零出现——R6/R7 守护）。各模块只经 api
 * 装配工厂触达（internal 跨模块不可见，R3）。
 */
@Configuration
class AppConfig {

  private final CraConfig config;

  AppConfig(CraConfig config) {
    this.config = config;
  }

  @Bean
  GitHubComponents gitHubComponents() {
    return GitHubAssembly.createComponents(config.githubApi(), config.githubToken());
  }

  @Bean
  GitPlatformAdapter gitPlatformAdapter(GitHubComponents components) {
    return components.adapter();
  }

  @Bean
  ReviewLedger reviewLedger(GitHubComponents components) {
    // ADR-0005：GitHub 评论账本构造器注入 CommentSource（同一实现的双端口视图）
    return StoreAssembly.createGitHubLedger(
        components.commentSource(),
        new org.cra.common.git.RepoRef(config.repoOwner(), config.repoName()));
  }

  @Bean
  LlmGateway llmGateway() {
    // T2.9：CI 与本地共用三档路由装配（05§5；--model-strong/--fallback-chain/--rpm 可调）
    return LlmAssembly.createGateway(LlmConfigFactory.create(config));
  }

  @Bean
  SubAgent<GeneralReviewInput> generalReviewer() {
    return AgentsAssembly.createGeneralReviewer();
  }

  @Bean
  org.cra.git.api.LocalRepoContext localRepoContext() {
    // 05§2.1：jgit blame 的本地仓库上下文；目录非仓库时 localRepoRoot 为空（合法降级形态）
    return org.cra.git.api.GitAssembly.createLocalRepoContext(
        java.nio.file.Path.of(config.workspace()));
  }

  @Bean
  org.cra.core.api.PipelineOptions pipelineOptions() {
    return new org.cra.core.api.PipelineOptions(
        config.runTotalTokens(),
        config.degradeAtFirst(),
        config.degradeAtSecond(),
        config.largeDiffLines(),
        config.hardCapLines(),
        config.nitBudget(),
        config.publishUnverified());
  }

  @Bean
  Orchestrator orchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      org.cra.core.api.PipelineOptions pipelineOptions,
      org.cra.git.api.LocalRepoContext localRepoContext) {
    return CoreAssembly.createOrchestrator(
        adapter,
        ledger,
        gateway,
        reviewer,
        java.nio.file.Path.of(config.runRoot()),
        pipelineOptions,
        localRepoContext);
  }
}
