package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 反锚定引用新颖性校验（03§4.2 v1.2 归一化口径：行号引用 {@code \bL?(\d{1,4})\b} 与 {@code :(\d{1,4})}
 * 归一为数字集合；代码片段按去空白行比较；重叠率 = 交集 / rationale 引用总数）。
 */
class AntiAnchorValidatorTest {

  private static final String FINDING =
      "L4: return items.get(items.size() - 1);\n当 items 为空时越界\n触发条件：空列表";

  @Test
  void passWhenRationaleIntroducesNewReferences() {
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check(FINDING, "独立复核：L3 与 L5 之间无空判，越界成立");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.PASS);
    assertThat(result.overlapRatio()).isLessThan(1.0);
    assertThat(result.refsTotal()).isEqualTo(2); // L3 + L5
  }

  @Test
  void failWhenRationaleOnlyRepeatsFindingReferences() {
    // 单行证据类真缺陷的正确复述必然高重叠——机器判定 FAIL，但语义上「不阻断」由晋升层保证
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check(FINDING, "确认 L4 处 return items.get(items.size() - 1); 越界");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.FAIL);
    assertThat(result.overlapRatio()).isEqualTo(1.0);
  }

  @Test
  void normalizedLineNumbersMatchAcrossFormats() {
    // "L4"、":4"、裸 "4" 归一为同一引用——rationale 写 :4 也算复述
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check("evidence at L4 only", "见 :4 与 L4 两处");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.FAIL);
  }

  @Test
  void passWhenRationaleHasNoExtractableReferences() {
    // 无引用可锚定 → PASS（无法建立锚定证据，不能据此判 FAIL）
    AntiAnchorValidator.Result result = AntiAnchorValidator.check(FINDING, "确认失败模式成立，触发条件为空输入");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.PASS);
    assertThat(result.refsTotal()).isZero();
  }

  @Test
  void fencedCodeLinesComparedWhitespaceStripped() {
    // 代码片段按去空白行比较：rationale 的围栏行与 finding 原文完全一致 → 计入交集
    String findingWithFence = "```\nreturn items.get(items.size() - 1);\n```";
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check(
            findingWithFence, "```\n  return items.get(items.size() - 1);  \n```");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.FAIL);
  }

  @Test
  void newFencedLineAloneMakesPass() {
    String findingWithFence = "```\nreturn items.get(items.size() - 1);\n```";
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check(
            findingWithFence,
            "```\nassertThrows(IndexOutOfBoundsException.class, () -> foo.get(List.of()));\n```");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.PASS);
  }

  @Test
  void fenceWithLanguageInfoStringIsRecognized() {
    // ```java 曾是"非围栏"：闭合围栏反而开启 inside，围栏外散文被当成代码行（重叠率被污染）
    String finding = "```java\ncode line\n```";
    AntiAnchorValidator.Result result =
        AntiAnchorValidator.check(finding, "```java\ncode line\n```\n额外说明无引用");
    assertThat(result.status()).isEqualTo(AntiAnchorValidator.Status.FAIL);
    assertThat(result.refsTotal()).isEqualTo(1);
  }
}
