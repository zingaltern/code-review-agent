package org.cra.runner.cli;

import java.nio.file.Path;
import org.cra.agents.api.AgentsAssembly;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.core.api.CoreAssembly;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.Orchestrator;
import org.cra.core.api.PipelineOptions;
import org.cra.core.api.SemanticContextOptions;
import org.cra.core.api.SignoffOptions;
import org.cra.git.api.GitAssembly;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.api.SymbolIndex;
import org.cra.llm.api.LlmAssembly;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个组合根共享的 bean 定义（10§3/10§4）：CI/GitHub 形态 {@link AppConfig} 与本地形态 {@link LocalAppConfig} 共用的 11 个
 * bean（llmGateway / reviewCrew / verifier / judge / localRepoContext / crossCheckerOptions /
 * pipelineOptions / signoffOptions / semanticContextOptions / symbolIndex /
 * orchestrator）只此一份，改配置语义不必两处同步。
 *
 * <p>形态差异只有平台侧：adapter / ledger / {@code @Primary}（本地形态）由子类提供，GitHub 形态另有 {@link
 * PlatformComponents}。Spring 处理配置类的完整类层次，继承的 @Bean 方法照常注册；构造器注入不变（R7：@Autowired 零出现）。
 */
@Configuration
abstract class AbstractAppConfig {

  private final CraConfig config;

  AbstractAppConfig(CraConfig config) {
    this.config = config;
  }

  /** 子类的平台侧 bean 读取同一份运行配置。 */
  final CraConfig config() {
    return config;
  }

  @Bean
  LlmGateway llmGateway() {
    // T2.9：CI 与本地共用三档路由装配（05§5；--model-strong/--fallback-chain/--rpm 可调）
    return LlmAssembly.createGateway(LlmConfigFactory.create(config));
  }

  @Bean
  ReviewCrew reviewCrew() {
    // T2.2：M2 评审班组（BugHunter 四视角；T2.3 扩展专角色）；05§5 agents.budget.<role>.* 在此施加
    return config.agentBudgets().apply(AgentsAssembly.createReviewCrew());
  }

  @Bean
  SubAgent<VerificationBatchInput> verifier() {
    return config.agentBudgets().apply(AgentsAssembly.createVerifier());
  }

  @Bean
  SubAgent<JudgeCaseInput> judge() {
    return config.agentBudgets().apply(AgentsAssembly.createJudge());
  }

  @Bean
  LocalRepoContext localRepoContext() {
    // 05§2.1：jgit blame 的本地仓库上下文；目录非仓库时 localRepoRoot 为空（合法降级形态）
    return GitAssembly.createLocalRepoContext(Path.of(config.workspace()));
  }

  @Bean
  CrossCheckerOptions crossCheckerOptions() {
    // 05§5 agents.crosschecker：默认关闭；模式串在 CLI 解析期已校验
    return new CrossCheckerOptions(
        config.crossCheckerEnabled(),
        CrossCheckerModeParser.parse(config.crossCheckerModes()),
        config.crossCheckerHistoryWeeks());
  }

  @Bean
  PipelineOptions pipelineOptions() {
    return new PipelineOptions(
        config.runTotalTokens(),
        config.degradeAtFirst(),
        config.degradeAtSecond(),
        config.largeDiffLines(),
        config.hardCapLines(),
        config.nitBudget(),
        config.publishUnverified(),
        config.spotCheckPercent(),
        config.includeTestFiles());
  }

  @Bean
  SignoffOptions signoffOptions() {
    // 05§5 agents.signoff（M4a）：开关与可信作者；本地形态同样生成签字建议（测试状态由实跑结果覆盖）
    return config.signoff().toOptions();
  }

  @Bean
  SemanticContextOptions semanticContextOptions() {
    // 05§5 semantic_context（M4e，ADR-0014）：默认关闭；开启后 standard/deep 档注入 L2 跨文件切片
    return config.semanticContext().toOptions();
  }

  @Bean
  SymbolIndex symbolIndex() {
    // ADR-0014：jgit 树读取 + Java 词法抽取；非 git checkout 时 build 返回不可得快照（不炸 run）
    return GitAssembly.createSymbolIndex(Path.of(config.workspace()));
  }

  @Bean
  Orchestrator orchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      PipelineOptions pipelineOptions,
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions,
      LocalRepoContext localRepoContext,
      SemanticContextOptions semanticContextOptions,
      SymbolIndex symbolIndex) {
    return CoreAssembly.createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        Path.of(config.runRoot()),
        pipelineOptions,
        localRepoContext,
        crossCheckerOptions,
        signoffOptions,
        // M4f-full 事件史端口未在 CLI 组合根装配（ADR-0012 默认关闭；L2/L3 不验证事件史路径）
        null,
        semanticContextOptions,
        symbolIndex);
  }
}
