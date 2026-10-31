package org.cra.context.api;

import org.cra.context.internal.DefaultContextAssembler;
import org.cra.context.internal.DefaultReviewPolicyParser;

/** cra-context 装配工厂（10§4：组合根与 cra-core 只经本工厂触达 internal 实现）。 */
public final class ContextAssembly {

  private ContextAssembly() {}

  /**
   * 创建默认上下文组装器（L0/L1 分层 + token 裁剪，04§1）。
   *
   * @return ContextAssembler
   */
  public static ContextAssembler createContextAssembler() {
    return new DefaultContextAssembler();
  }

  /**
   * 创建默认 REVIEW.md 解析器（04§3.1；解析容错，段落缺失优雅跳过）。
   *
   * @return ReviewPolicyParser
   */
  public static ReviewPolicyParser createReviewPolicyParser() {
    return new DefaultReviewPolicyParser();
  }
}
