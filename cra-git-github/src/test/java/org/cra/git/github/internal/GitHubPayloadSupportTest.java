package org.cra.git.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** glob→regex 语义单测（05§2.1 扫描范围）：双星号后跟斜杠匹配零或多级目录，既有语义不回退。 */
class GitHubPayloadSupportTest {

  private static boolean matches(String glob, String path) {
    return GitHubPayloadSupport.globToRegex(glob).matcher(path).matches();
  }

  @Test
  void doubleStarSlashMatchesZeroOrMoreDirectories() {
    assertThat(matches("**/foo", "foo")).isTrue();
    assertThat(matches("**/foo", "a/foo")).isTrue();
    assertThat(matches("**/foo", "a/b/foo")).isTrue();
    assertThat(matches("**/foo", "a/foobar")).isFalse();
  }

  @Test
  void doubleStarSlashInMiddleMatchesBothForms() {
    assertThat(matches("a/**/b", "a/b")).isTrue();
    assertThat(matches("a/**/b", "a/x/y/b")).isTrue();
    assertThat(matches("a/**/b", "a/x/y/c")).isFalse();
  }

  @Test
  void existingGlobSemanticsArePreserved() {
    // 既有用例不回退：src/**/*.java 仍命中多级目录
    assertThat(matches("src/**/*.java", "src/main/java/A.java")).isTrue();
    assertThat(matches("src/**/*.java", "src/test/B.java")).isTrue();
    // 零目录同级文件同样命中（修复目标）
    assertThat(matches("src/**/*.java", "src/A.java")).isTrue();
    // 单 * 不跨目录；独立 ** 任意匹配（含斜杠）
    assertThat(matches("docs/*", "docs/readme.md")).isTrue();
    assertThat(matches("docs/*", "docs/a/readme.md")).isFalse();
    assertThat(matches("**", "docs/a/readme.md")).isTrue();
    assertThat(matches("src/**", "src/a/b/Foo.java")).isTrue();
    // ? 与字面量转义保持原语义
    assertThat(matches("a?c", "abc")).isTrue();
    assertThat(matches("a.c", "abc")).isFalse();
  }
}
