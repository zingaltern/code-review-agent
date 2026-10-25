package org.cra.core.api;

import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;

/**
 * 编排器入口（02§1 L4；02§5：输入 ReviewRequest，输出 ReviewResult——core 全部逻辑与形态无关， CLI/server 只是两种壳）。run
 * 级不可恢复故障在实现内统一捕获：落盘 FAILED + trace 后以 FAILED 结果返回（02§4.2），不向调用方抛异常。
 */
public interface Orchestrator {

  /**
   * 执行一次评审 run。
   *
   * @param request 评审请求
   * @return run 终态结果（SUCCESS/DEGRADED/FAILED/SKIPPED）
   */
  ReviewResult run(ReviewRequest request);
}
