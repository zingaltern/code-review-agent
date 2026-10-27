package org.cra.runner.cli;

import org.cra.agents.api.AgentsAssembly;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.core.api.CoreAssembly;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.llm.api.LlmAssembly;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组合根（10§3/10§4：M0–M3 全部对象图在 cra-runner-cli 装配；Spring 仅 DI/配置，@Autowired 零出现——R6/R7 守护）。各模块只经 api
 * 装配工厂触达（internal 跨模块不可见，R3）。
 *
 * <p>M4e 第二半（ADR-0013）：Git 平台按 {@link CraConfig#platform()} 装配——GitHub（默认）或 GitLab；两者都不依赖对方模块。
 */
@Configuration
class AppConfig {

  private final CraConfig config;

  AppConfig(CraConfig config) {
    this.config = config;
  }

  @Bean
  PlatformComponents platformComponents() {
    // ADR-0013：未选中的平台不构造、不读取其 token；GitLab 模式不需要 githubToken，反之亦然
    return PlatformComponents.create(config);
  }

  @Bean
  GitPlatformAdapter gitPlatformAdapter(PlatformComponents components) {
    return components.adapter();
  }

  @Bean
  ReviewLedger reviewLedger(PlatformComponents components) {
    // ADR-0005：评论账本构造器注入 CommentSource（同一实现的双端口视图）；
    // M4c/M4d：发布形态决定账本载体（PR 评论 / commit comment / issue，05§1.3）
    return StoreAssembly.createGitHubLedger(
        components.commentSource(),
        new org.cra.common.git.RepoRef(config.repoOwner(), config.repoName()),
        config.reviewMode());
  }

  @Bean
  LlmGateway llmGateway() {
    // T2.9：CI 与本地共用三档路由装配（05§5；--model-strong/--fallback-chain/--rpm 可调）
    return LlmAssembly.createGateway(LlmConfigFactory.create(config));
  }

  @Bean
  ReviewCrew reviewCrew() {
    // T2.2：M2 评审班组（BugHunter 四视角；T2.3 扩展专角色）
    return AgentsAssembly.createReviewCrew();
  }

  @Bean
  SubAgent<VerificationBatchInput> verifier() {
    return AgentsAssembly.createVerifier();
  }

  @Bean
  SubAgent<JudgeCaseInput> judge() {
    return AgentsAssembly.createJudge();
  }

  @Bean
  org.cra.git.api.LocalRepoContext localRepoContext() {
    // 05§2.1：jgit blame 的本地仓库上下文；目录非仓库时 localRepoRoot 为空（合法降级形态）
    return org.cra.git.api.GitAssembly.createLocalRepoContext(
        java.nio.file.Path.of(config.workspace()));
  }

  @Bean
  org.cra.core.api.CrossCheckerOptions crossCheckerOptions() {
    // 05§5 agents.crosschecker：默认关闭；模式串在 CLI 解析期已校验
    return new org.cra.core.api.CrossCheckerOptions(
        config.crossCheckerEnabled(),
        CrossCheckerModeParser.parse(config.crossCheckerModes()),
        config.crossCheckerHistoryWeeks());
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
        config.publishUnverified(),
        config.spotCheckPercent());
  }

  @Bean
  org.cra.core.api.SignoffOptions signoffOptions() {
    // 05§5 agents.signoff（M4a）：开关与可信作者；路径分级规则取内置默认
    return config.signoff().toOptions();
  }

  @Bean
  Orchestrator orchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      org.cra.core.api.PipelineOptions pipelineOptions,
      org.cra.core.api.CrossCheckerOptions crossCheckerOptions,
      org.cra.core.api.SignoffOptions signoffOptions,
      org.cra.git.api.LocalRepoContext localRepoContext) {
    return CoreAssembly.createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        java.nio.file.Path.of(config.runRoot()),
        pipelineOptions,
        localRepoContext,
        crossCheckerOptions,
        signoffOptions);
  }
}
