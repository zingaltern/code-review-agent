package org.cra.common.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 处置信号辅助单测（06§1.1）：excerpt 的 null/blank 守卫、空白折叠与 200 字符截断。
 *
 * <p>单一事实源（ADR-0016）：原 cra-git-github / cra-git-gitlab 两份同文用例合并于此；replyTag 与时间字段容错由两个 平台适配器的
 * WireMock 契约测试按端到端覆盖。
 */
class DispositionSupportTest {

  @Test
  void excerptReturnsNullForNullOrBlankBody() {
    assertThat(DispositionSupport.excerpt(null)).isNull();
    assertThat(DispositionSupport.excerpt("   ")).isNull();
    assertThat(DispositionSupport.excerpt("  \n\t ")).isNull();
  }

  @Test
  void excerptFoldsWhitespaceAndTruncates() {
    assertThat(DispositionSupport.excerpt("fixed\n\nthanks  ")).isEqualTo("fixed thanks");
    assertThat(DispositionSupport.excerpt(":wrong:\n    实际是设计如此")).isEqualTo(":wrong: 实际是设计如此");
    assertThat(DispositionSupport.excerpt("x".repeat(201))).hasSize(201).endsWith("…");
  }
}
