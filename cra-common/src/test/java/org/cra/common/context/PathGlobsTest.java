package org.cra.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 路径 glob 语义单测（04§3.1 Priority paths / Skip paths；05§2.1 listFiles 扫描）：双星号加斜杠匹配零或多级目录、
 * 裸双星号跨段、单星号单段、{@code ?} 单段通配（不跨 {@code /}，ADR-0016 收紧），既有语义不回退。
 *
 * <p>单一事实源（ADR-0016）：原 cra-common / cra-context 两份 PathGlobsTest 与 github/gitlab 两份
 * PayloadSupportTest 的 glob 用例合并于此——三个适配器的私有 globToRegex 删除后，这是 glob 语义的唯一测试位。
 */
class PathGlobsTest {

  private static boolean matches(String glob, String path) {
    return PathGlobs.matches(glob, path);
  }

  @Test
  void doubleStarSlashMatchesZeroOrMoreDirectories() {
    assertThat(matches("**/foo", "foo")).isTrue();
    assertThat(matches("**/foo", "a/foo")).isTrue();
    assertThat(matches("**/foo", "a/b/foo")).isTrue();
    assertThat(matches("**/foo", "a/foobar")).isFalse();
    assertThat(PathGlobs.matches("**/generated/**", "generated/Foo.java")).isTrue();
    assertThat(PathGlobs.matches("**/generated/**", "a/b/generated/Foo.java")).isTrue();
  }

  @Test
  void doubleStarSlashInMiddleMatchesBothForms() {
    // `a/**/b` 双形态：零目录（a/b）与多级目录（a/x/y/b）同样命中
    assertThat(matches("a/**/b", "a/b")).isTrue();
    assertThat(matches("a/**/b", "a/x/y/b")).isTrue();
    assertThat(matches("a/**/b", "a/x/y/c")).isFalse();
  }

  @Test
  void bareDoubleStarCrossesSegments() {
    // 裸 ** 跨段（含斜杠）；** 前缀须按段命中，不得吃进前缀字符
    assertThat(matches("**", "docs/a/readme.md")).isTrue();
    assertThat(matches("src/**", "src/a/b/Foo.java")).isTrue();
    assertThat(matches("generated/**", "generated/code/x.java")).isTrue();
    assertThat(matches("generated/**", "generated/x.java")).isTrue();
    assertThat(matches("generated/**", "src/generated/x.java")).isFalse();
    assertThat(matches("src/**", "srcx/a.java")).isFalse();
  }

  @Test
  void singleStarDoesNotCrossSegments() {
    assertThat(matches("billing/*", "billing/Foo.java")).isTrue();
    assertThat(matches("billing/*", "billing/a/b")).isFalse();
    assertThat(matches("docs/*", "docs/readme.md")).isTrue();
    assertThat(matches("docs/*", "docs/a/readme.md")).isFalse();
    assertThat(matches("src/*.java", "src/Foo.java")).isTrue();
    assertThat(matches("src/*.java", "src/sub/Foo.java")).isFalse();
  }

  @Test
  void multiLevelSuffixGlobMatchesSameLevelFile() {
    // 既有用例不回退：src/**/*.java 零目录同级文件命中（当年修复目标）
    assertThat(matches("src/**/*.java", "src/main/java/A.java")).isTrue();
    assertThat(matches("src/**/*.java", "src/test/B.java")).isTrue();
    assertThat(matches("src/**/*.java", "src/A.java")).isTrue();
    assertThat(matches("**/*.java", "a/b/C.java")).isTrue();
    assertThat(matches("**/*.java", "C.java")).isTrue();
  }

  @Test
  void questionMarkIsSingleSegmentWildcard() {
    // ADR-0016：? 由字面量收紧为单段通配（[^/]）——单段内命中，不跨 /
    assertThat(matches("a?c", "abc")).isTrue();
    assertThat(matches("a?c", "a/c")).isFalse();
    assertThat(matches("src/?.java", "src/A.java")).isTrue();
    assertThat(matches("src/?.java", "src/AB.java")).isFalse();
  }

  @Test
  void regexMetacharactersAreLiteral() {
    // 其余字符按字面匹配：. 等正则元字符不展开
    assertThat(matches("a.c", "abc")).isFalse();
    assertThat(matches("a.c", "a.c")).isTrue();
    assertThat(matches("src/Foo.java", "src/Foo.java")).isTrue();
    assertThat(matches("src/Foo.java", "src/Bar.java")).isFalse();
  }

  @Test
  void exactPathMatches() {
    assertThat(matches("src/Foo.java", "src/Foo.java")).isTrue();
    assertThat(matches("src/Foo.java", "src/Bar.java")).isFalse();
  }

  @Test
  void blankGlobNeverMatches() {
    assertThat(matches("", "src/Foo.java")).isFalse();
    assertThat(matches("  ", "src/Foo.java")).isFalse();
    assertThat(matches("src/**", "")).isFalse();
    assertThat(matches("src/**", null)).isFalse();
  }

  @Test
  void matchesAnyShortCircuitsOnFirstHit() {
    assertThat(PathGlobs.matchesAny(List.of("a/**", "src/**"), "src/Foo.java")).isTrue();
    assertThat(PathGlobs.matchesAny(List.of(), "src/Foo.java")).isFalse();
  }

  @Test
  void compileProducesAnchoredPatternForAdapterLoops() {
    // 适配器（文件树遍历）直接复用 compile 的 Pattern：锚定全串匹配，循环内复用同一实例
    Pattern p = PathGlobs.compile("src/**/*.java");
    assertThat(p.matcher("src/main/java/A.java").matches()).isTrue();
    assertThat(p.matcher("xsrc/main/A.java").matches()).isFalse();
    assertThat(p.matcher("src/A.java").matches()).isTrue();
  }
}
