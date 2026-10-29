package org.cra.git.gitlab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 处置信号辅助（06§1.1）：excerpt 的 null 守卫与原有摘要语义。 */
class GitLabDispositionSupportTest {

  @Test
  void excerptNullBodyYieldsNull() {
    assertThat(GitLabDispositionSupport.excerpt(null)).isNull();
  }

  @Test
  void excerptBlankBodyYieldsNull() {
    assertThat(GitLabDispositionSupport.excerpt("  \n\t ")).isNull();
  }

  @Test
  void excerptCollapsesWhitespaceAndTruncates() {
    assertThat(GitLabDispositionSupport.excerpt(":wrong:\n    实际是设计如此"))
        .isEqualTo(":wrong: 实际是设计如此");
    assertThat(GitLabDispositionSupport.excerpt("x".repeat(201))).hasSize(201).endsWith("…");
  }
}
