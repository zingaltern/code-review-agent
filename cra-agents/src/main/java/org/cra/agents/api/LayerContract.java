package org.cra.agents.api;

/**
 * 分层核对契约（跨层上下文索引的提示词固定段；ADR-0015 §2.2 修订、04§2.6）。
 *
 * <p>同一份文本注入两处（措辞必须一致，避免两套纪律）：BH-dataflow 评审 brief（语义索引可用时）与 Verifier 验证 brief
 * （调用点索引可用时）。它是**编排器指令**（可信），不进 UNTRUSTED 定界。
 *
 * <p>纪律来源：跨层调用点索引是词法级、有界的——未列出的层级往往只是"看不见"，不是"不存在"；把看不见当不存在是 验证层误拒（REFUTED）与评审层漏报的共同根源（ADR-0015
 * §1.2）。
 */
public final class LayerContract {

  /** 分层核对契约固定段（含标题；不可用时整段不注入）。 */
  public static final String SECTION =
      """
      ## 分层核对契约（编排器注入）
      按 controller→service→DAO 核对：变更方法的调用方是否依赖其返回值/空值/事务边界；
      简报未列出的层级视为不可见，不得据此臆断。
      """;

  private LayerContract() {}
}
