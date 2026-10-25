package org.cra.core.api;

import java.nio.file.Path;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * cra-core 装配工厂（10§4：组合根只调工厂；SubAgent 实例由组合根经各模块 Assembly 产生后注入， core 只面向 SubAgent 接口编程——10§8 第 4
 * 条）。cra-context 的 ContextAssembler/ReviewPolicyParser 由本工厂经 ContextAssembly 内部创建（core→context api
 * 合法依赖，10§2 矩阵）。
 */
public final class CoreAssembly {

  private CoreAssembly() {}

  /**
   * 创建 M2 编排器（07 T2.4–T2.8：M1 流水线 + 独立复核验证波 + 判定晋升规则 + Judge 仲裁/抽查 + 收敛控制 + run 内精确去重
   * merge；幂等闸与发布沿用既有契约，02§4.1）。
   *
   * @param adapter Git 平台适配器（diff/readFile/postReview）
   * @param ledger 评审账本（commit 闸 + 指纹级去重 + 轮次计数）
   * @param gateway LLM 网关
   * @param reviewer 评审子代理定义
   * @param verifier 验证子代理定义（T2.4，balanced-review）
   * @param judge 仲裁子代理定义（T2.6，arbitrate）
   * @param runRoot trace/brief 落盘根目录（如 .cra/runs）
   * @param options 编排参数（null 用默认）
   * @param localRepoContext 本地仓库上下文（jgit blame + 被引 commit diff；null 时相应检测降级声明）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      SubAgent<VerificationBatchInput> verifier,
      SubAgent<JudgeCaseInput> judge,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext) {
    return new org.cra.core.internal.ReviewPipeline(
        adapter,
        ledger,
        gateway,
        reviewer,
        verifier,
        judge,
        runRoot,
        options == null ? PipelineOptions.defaults() : options,
        localRepoContext);
  }
}
