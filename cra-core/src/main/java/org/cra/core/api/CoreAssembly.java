package org.cra.core.api;

import java.nio.file.Path;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.common.event.EventHistoryQuery;
import org.cra.core.internal.ReviewPipeline;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.api.SymbolIndex;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * cra-core 装配工厂（10§4：组合根只调工厂；评审班组由组合根经 {@code AgentsAssembly} 产生后注入， core 只面向 SubAgent/ReviewCrew
 * 接口编程——10§8 第 4 条）。cra-context 的 ContextAssembler/ReviewPolicyParser 由流水线经 ContextAssembly
 * 内部创建（core→context api 合法依赖，10§2 矩阵）。
 */
public final class CoreAssembly {

  private CoreAssembly() {}

  /**
   * 创建 M2 编排器（07 T2.1–T2.8：隔离运行时视角路由 + 独立复核验证波 + 晋升规则 + Judge 仲裁/抽查 + 收敛控制 + run 内精确去重
   * merge；幂等闸与发布沿用既有契约，02§4.1）。
   *
   * @param adapter Git 平台适配器（diff/readFile/postReview）
   * @param ledger 评审账本（commit 闸 + 指纹级去重 + 轮次计数）
   * @param gateway LLM 网关
   * @param crew 评审班组（BugHunter 视角集合，T2.2/T2.3）
   * @param verifier 验证子代理定义（T2.4，balanced-review）
   * @param judge 仲裁子代理定义（T2.6，arbitrate）
   * @param runRoot trace/brief 落盘根目录（如 .cra/runs）
   * @param options 编排参数（null 用默认；默认 {@code includeTestFiles=false}——测试文件不进入评审范围，02§4.4）
   * @param localRepoContext 本地仓库上下文（jgit blame + 被引 commit diff；null 时相应检测降级声明）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext) {
    return createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options,
        localRepoContext,
        CrossCheckerOptions.disabled());
  }

  /**
   * 创建 M4f-lite 形态编排器（追加 CrossChecker 配置；默认关闭时与 M2 形态完全一致，04§2.2 基线算术不变）。
   *
   * @param crossCheckerOptions CrossChecker 触发配置（05§5 agents.crosschecker；null = 关闭）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions) {
    return createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options,
        localRepoContext,
        crossCheckerOptions,
        SignoffOptions.disabled());
  }

  /**
   * 创建 M4a 形态编排器（追加签字建议配置；disabled 时与 M4f-lite 形态完全一致）。
   *
   * @param signoffOptions 签字建议配置（05§5 agents.signoff；null 用默认，默认生成建议）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions) {
    return createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options,
        localRepoContext,
        crossCheckerOptions,
        signoffOptions,
        null);
  }

  /**
   * 创建全参形态编排器（M4f 事件史 + M4e 语义上下文 + 符号索引；ADR-0012/0014）。
   *
   * @param eventHistoryQuery 事件史检索端口（cross-checker 的 context 类外部证据面，ADR-0012；null = 未装配）
   * @param semanticContextOptions 语义上下文配置（05§5 semantic_context；null 用关闭态）
   * @param symbolIndex 语义索引端口（null = 未装配；开启时按不可得声明）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions,
      EventHistoryQuery eventHistoryQuery,
      SemanticContextOptions semanticContextOptions,
      SymbolIndex symbolIndex) {
    return new ReviewPipeline(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options == null ? PipelineOptions.defaults() : options,
        localRepoContext,
        crossCheckerOptions,
        signoffOptions == null ? SignoffOptions.defaults() : signoffOptions,
        eventHistoryQuery,
        semanticContextOptions == null ? SemanticContextOptions.disabled() : semanticContextOptions,
        symbolIndex);
  }

  /**
   * 创建 M4f-full 形态编排器（追加事件史查询端口；null = 未装配，CrossChecker 声明事件史不可得）。
   *
   * @param eventHistoryQuery 事件史检索端口（ADR-0012）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      ReviewCrew crew,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions,
      SignoffOptions signoffOptions,
      EventHistoryQuery eventHistoryQuery) {
    return createOrchestrator(
        adapter,
        ledger,
        gateway,
        crew,
        verifier,
        judge,
        runRoot,
        options,
        localRepoContext,
        crossCheckerOptions,
        signoffOptions,
        eventHistoryQuery,
        SemanticContextOptions.disabled(),
        null);
  }
}
