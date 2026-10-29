package org.cra.git.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 处置信号摘录（05§2.2）：null/blank 守卫与折叠截断。 */
class GitHubDispositionSupportTest {

  @Test
  void excerptReturnsNullForNullOrBlankBody() {
    assertThat(GitHubDispositionSupport.excerpt(null)).isNull();
    assertThat(GitHubDispositionSupport.excerpt("   ")).isNull();
  }

  @Test
  void excerptFoldsWhitespaceAndTruncates() {
    assertThat(GitHubDispositionSupport.excerpt("fixed\n\nthanks  ")).isEqualTo("fixed thanks");
  }
}
