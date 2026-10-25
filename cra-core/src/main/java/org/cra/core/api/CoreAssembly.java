package org.cra.core.api;

import java.nio.file.Path;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.SubAgent;
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
   * 创建 M1 编排器（07 T1.3–T1.7：分层上下文 + REVIEW.md 策略 + 深度档位 + 大 diff 分 chunk + 降档阶梯 + nit 预算/severity 排序
   * + jgit blame 复核；幂等闸与发布沿用 M0 契约，02§4.1）。
   *
   * @param adapter Git 平台适配器（diff/readFile/postReview）
   * @param ledger 评审账本（commit 闸 + 指纹级去重）
   * @param gateway LLM 网关
   * @param reviewer 评审子代理定义（M1 仍单角色）
   * @param runRoot trace/brief 落盘根目录（如 .cra/runs）
   * @param options 编排参数（null 用默认）
   * @param localRepoContext 本地仓库上下文（jgit blame；null 或仓库不可得时 pre-existing 复核降级声明，05§2.1）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      Path runRoot,
      PipelineOptions options,
      LocalRepoContext localRepoContext) {
    return new org.cra.core.internal.ReviewPipeline(
        adapter,
        ledger,
        gateway,
        reviewer,
        runRoot,
        options == null ? PipelineOptions.defaults() : options,
        localRepoContext);
  }
}
