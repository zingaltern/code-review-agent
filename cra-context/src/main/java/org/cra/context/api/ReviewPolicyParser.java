package org.cra.context.api;

import org.cra.common.context.ReviewPolicy;

/**
 * REVIEW.md 解析器（04§3.1 格式；base 分支版本由编排器读取后传入）。解析容错：任何输入不抛异常， 异常形态降级为 warnings + 空段落（04§3.2 规则
 * 4：解析失败按最小默认策略运行，不阻塞评审）。
 */
public interface ReviewPolicyParser {

  /**
   * 解析 REVIEW.md 文本（04§3.1 全部段落类型，含 v1.3 三新段落；段落缺失优雅跳过）。
   *
   * @param markdown REVIEW.md 全文（null/空白视为空策略）
   * @return 解析结果（warnings 累积解析警告；present 表示入参非空）
   */
  ReviewPolicy parse(String markdown);
}
