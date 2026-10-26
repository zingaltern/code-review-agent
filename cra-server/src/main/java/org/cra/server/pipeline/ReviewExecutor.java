package org.cra.server.pipeline;

import org.cra.common.run.ReviewResult;
import org.cra.server.queue.ReviewJob;

/** 评审执行端口（生产实现 = cra-core Orchestrator；测试可替换为脚本执行器）。 */
public interface ReviewExecutor {

  /**
   * 执行一次评审（幂等由 core 的 commit 闸 + 账本指纹负责）。
   *
   * @param job 评审任务
   * @return run 结果
   */
  ReviewResult execute(ReviewJob job);
}
