package org.cra.core.api;

import java.nio.file.Path;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.SubAgent;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * cra-core 装配工厂（10§4：组合根只调工厂；SubAgent 实例由组合根经各模块 Assembly 产生后注入， core 只面向 SubAgent 接口编程——10§8 第 4
 * 条）。
 */
public final class CoreAssembly {

  private CoreAssembly() {}

  /**
   * 创建 M0 线性流水线编排器（assemble → review(×1) → publish，07 T0.5）。
   *
   * @param adapter Git 平台适配器（diff/readFile/postReview）
   * @param ledger 评审账本（commit 闸 + 指纹级去重）
   * @param gateway LLM 网关
   * @param reviewer 评审子代理定义（M0 = GeneralReviewerDefinition）
   * @param runRoot trace/brief 落盘根目录（如 .cra/runs）
   * @return Orchestrator
   */
  public static Orchestrator createOrchestrator(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      Path runRoot) {
    return new org.cra.core.internal.ReviewPipeline(adapter, ledger, gateway, reviewer, runRoot);
  }
}
