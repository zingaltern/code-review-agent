package org.cra.git.api;

import org.cra.common.semantic.SymbolIndexOptions;
import org.cra.common.semantic.SymbolIndexSnapshot;

/**
 * 语义索引端口（M4e 池内项"语义索引 / 跨文件上下文"第一版；ADR-0014）。
 *
 * <p>定位：给评审 brief 提供 L2 跨文件上下文的**确定性**数据面——从 head 侧源码抽取类型/方法/字段定义、符号引用、import 关系与文件级引用计数，
 * 供编排器组装"改了某符号 → 谁在调用/谁 import 了它"的切片。它不是检索/向量/LLM 能力，也不做类型绑定。
 *
 * <p>实现约束（ADR-0014）：本地、无外部服务、无持久化；索引在评审期间按 head 构建于内存，按路径升序与固定上限扫描， 同 head +
 * 同选项必须产出逐字段相等的快照（可重放）。第一版仅支持 Java 源文件，其余文件跳过并在快照 notes 中声明。
 *
 * <p>"不可得"语义（11§4 第 5 条）：无本地仓库 / head 不可解析 / 构建失败 / 超限策略为 UNAVAILABLE 时返回 {@link
 * SymbolIndexSnapshot#unavailable(String, String)}——不抛异常、不炸 run，调用方降级为覆盖声明。
 */
public interface SymbolIndex {

  /**
   * 按 head 构建（或复用，实现可缓存）符号索引快照。
   *
   * <p>确定性契约：同一 {@code headSha} + 同一 {@code options} 的两次调用必须返回 {@code equals} 的快照；
   * 实现不得读取工作区未提交内容、不得以运行时钟或并发顺序影响输出。
   *
   * @param headSha 索引锚点 commit（完整或缩写 sha；空 = 不可得）
   * @param options 构建选项（null 用默认；扫描上限/过滤/超限策略）
   * @return 快照；不可得时为 {@link SymbolIndexSnapshot#unavailable(String, String)}，不抛异常
   */
  SymbolIndexSnapshot build(String headSha, SymbolIndexOptions options);

  /**
   * 默认选项构建（maxFiles/maxFileBytes 取生产默认，超限截断并声明）。
   *
   * @param headSha 索引锚点 commit
   * @return 快照；不可得时为不可得快照
   */
  default SymbolIndexSnapshot build(String headSha) {
    return build(headSha, SymbolIndexOptions.defaults());
  }
}
