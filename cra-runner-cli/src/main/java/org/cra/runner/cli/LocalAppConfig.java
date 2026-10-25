package org.cra.runner.cli;

import java.nio.file.Path;
import org.cra.agents.api.AgentsAssembly;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.core.api.CoreAssembly;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalGitAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.llm.api.LlmAssembly;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地 pre-PR 模式组合根（02§5 本地形态；T2.10）：账本 = SQLite（--ledger-db，默认 ~/.cra/ledger.db）、 diff 数据面 = 本地
 * jgit（LocalGitAdapter，由命令层打开后注入）、无任何 GitHub 依赖（不建 GitHub bean）。 Spring 仅 DI/配置（R6/R7）。
 */
@Configuration
class LocalAppConfig {

  private final CraConfig config;
  private final LocalGitAdapter localGit;

  LocalAppConfig(CraConfig config, LocalGitAdapter localGit) {
    this.config = config;
    this.localGit = localGit;
  }

  @Bean
  GitPlatformAdapter gitPlatformAdapter() {
    return localGit;
  }

  @Bean
  ReviewLedger reviewLedger() {
    // 本地形态事实源（02§5）：完成标记/finding 指纹/模板版本落 SQLite，幂等闸跨 run 生效
    return StoreAssembly.createSqliteLedger(
        config.repoOwner(), config.repoName(), Path.of(config.ledgerDb()));
  }

  @Bean
  LlmGateway llmGateway() {
    // 本地模式仅在 CRA_LLM_API_KEY 在场时被调用（命令层守卫），T2.9 三档路由配置同 CI 形态
    return LlmAssembly.createGateway(LlmConfigFactory.create(config));
  }

  @Bean
  ReviewCrew reviewCrew() {
    // T2.2/T2.3：M2 评审班组（BugHunter 四视角 + 专角色）
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
  LocalRepoContext localRepoContext() {
    return org.cra.git.api.GitAssembly.createLocalRepoContext(Path.of(config.workspace()));
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
  Orchestrator orchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      org.cra.core.api.PipelineOptions pipelineOptions,
      LocalRepoContext localRepoContext) {
    return CoreAssembly.createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        Path.of(config.runRoot()),
        pipelineOptions,
        localRepoContext);
  }
}
