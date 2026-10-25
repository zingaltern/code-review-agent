package org.cra.agents.api;

/**
 * 子代理统一抽象（03§1 五件套的 M0 形态：role/brief/schema→输出契约/tools/budget）。 无状态、单向通信——一切输入来自 brief；LLM 执行循环由
 * cra-core 驱动（10§2：子代理是纯定义， 执行器 *Agent 属编排侧），M2 T2.1 引入隔离运行时与受限工具循环。
 *
 * @param <I> 编排器注入的输入类型
 */
public interface SubAgent<I> {

  /**
   * 角色 + 模板版本标识。
   *
   * @return 角色标识
   */
  AgentRole role();

  /**
   * 由编排器注入输入，产出自包含 brief（03§2.1 上下文隔离的载体）。
   *
   * @param input 编排器组装的输入
   * @return 自包含简报
   */
  AgentBrief buildBrief(I input);

  /**
   * 工具白名单（默认只读，03§2.3）。
   *
   * @return 白名单
   */
  ToolWhitelist tools();

  /**
   * 三重预算硬限（03§6）。
   *
   * @return 预算
   */
  AgentBudget budget();
}
