package org.cra.context.api;

import org.cra.common.context.AssembledContext;
import org.cra.common.context.ContextInput;

/**
 * 上下文组装器（04§1 分层上下文模型；02§3：diff 解析、窗口/全文策略、切片、预算裁剪）。 纯函数式组装：不发起平台调用，文件全文由编排器预取后经 {@link
 * ContextInput} 内联。
 */
public interface ContextAssembler {

  /**
   * 组装单批（chunk）变更的 L0 diff 与 L1 上下文（04§1：窗口默认 ±30 + 条件全文； 裁剪阶梯 = 窗口收窄 → L1 整段丢弃，L0 永不丢弃，丢弃决策入
   * trimDecisions）。
   *
   * @param input 组装输入（chunk 文件 + 预取全文 + 窗口参数 + L1 预算）
   * @return 组装结果（含覆盖声明与裁剪决策）
   */
  AssembledContext assemble(ContextInput input);
}
