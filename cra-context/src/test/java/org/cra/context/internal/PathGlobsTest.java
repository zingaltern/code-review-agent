package org.cra.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.context.PathGlobs;
import org.junit.jupiter.api.Test;

/** 路径 glob 语义（04§3.1 Priority paths / Skip paths：** 跨目录、* 单段）。 */
class PathGlobsTest {

  @Test
  void doubleStarCrossesDirectories() {
    assertThat(PathGlobs.matches("generated/**", "generated/code/x.java")).isTrue();
    assertThat(PathGlobs.matches("generated/**", "generated/x.java")).isTrue();
    assertThat(PathGlobs.matches("generated/**", "src/generated/x.java")).isFalse();
    assertThat(PathGlobs.matches("src/**", "srcx/a.java")).isFalse();
  }

  @Test
  void singleStarWithinSegment() {
    assertThat(PathGlobs.matches("src/*.java", "src/Foo.java")).isTrue();
    assertThat(PathGlobs.matches("src/*.java", "src/sub/Foo.java")).isFalse();
  }

  @Test
  void exactPathMatches() {
    assertThat(PathGlobs.matches("src/Foo.java", "src/Foo.java")).isTrue();
    assertThat(PathGlobs.matches("src/Foo.java", "src/Bar.java")).isFalse();
  }

  @Test
  void blankGlobNeverMatches() {
    assertThat(PathGlobs.matches("", "src/Foo.java")).isFalse();
    assertThat(PathGlobs.matches("  ", "src/Foo.java")).isFalse();
    assertThat(PathGlobs.matches("src/**", "")).isFalse();
  }

  @Test
  void matchesAnyShortCircuitsOnFirstHit() {
    assertThat(PathGlobs.matchesAny(List.of("a/**", "src/**"), "src/Foo.java")).isTrue();
    assertThat(PathGlobs.matchesAny(List.of(), "src/Foo.java")).isFalse();
  }
}
