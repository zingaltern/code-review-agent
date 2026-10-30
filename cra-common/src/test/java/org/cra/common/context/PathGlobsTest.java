package org.cra.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 路径 glob 语义（04§3.1）：双星号跨目录段，双星号加斜杠匹配零或多级目录。 */
class PathGlobsTest {

  @Test
  void doubleStarSlashMatchesZeroLevelDirectory() {
    assertThat(PathGlobs.matches("**/generated/**", "generated/Foo.java")).isTrue();
    assertThat(PathGlobs.matches("**/generated/**", "a/b/generated/Foo.java")).isTrue();
    assertThat(PathGlobs.matches("a/**/b", "a/b")).isTrue();
    assertThat(PathGlobs.matches("a/**/b", "a/x/y/b")).isTrue();
  }

  @Test
  void singleStarDoesNotCrossSegments() {
    assertThat(PathGlobs.matches("billing/*", "billing/a/b")).isFalse();
    assertThat(PathGlobs.matches("billing/*", "billing/Foo.java")).isTrue();
  }
}
