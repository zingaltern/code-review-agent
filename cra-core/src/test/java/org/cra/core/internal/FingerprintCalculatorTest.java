package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 指纹 v2 语义单测（02§4.1：内容锚，不含 LLM 文本与绝对行号）。 */
class FingerprintCalculatorTest {

  @Test
  void stableAcrossLineShiftsWithSameContent() {
    // 上方插 10 行：绝对行号变化、内容不变 → 指纹不变
    String anchor = "return items.get(items.size() - 1);";
    List<String> context = List.of("a", "b", anchor, "d", "e");
    String fp1 = FingerprintCalculator.fingerprint("src/Foo.java", "bug", anchor, context);
    String fp2 = FingerprintCalculator.fingerprint("src/Foo.java", "bug", anchor, context);
    assertThat(fp1).isEqualTo(fp2);
    assertThat(fp1).hasSize(16).matches("[0-9a-f]+");
  }

  @Test
  void changesWhenAnchorContentChanges() {
    String fp1 =
        FingerprintCalculator.fingerprint(
            "src/Foo.java",
            "bug",
            "return size - 1;",
            List.of("a", "b", "return size - 1;", "d", "e"));
    String fp2 =
        FingerprintCalculator.fingerprint(
            "src/Foo.java",
            "bug",
            "return size - 2;",
            List.of("a", "b", "return size - 2;", "d", "e"));
    assertThat(fp1).isNotEqualTo(fp2);
  }

  @Test
  void distinguishesCategoryAndFile() {
    String anchor = "x";
    String fpBug = FingerprintCalculator.fingerprint("f.java", "bug", anchor, List.of(anchor));
    String fpSec = FingerprintCalculator.fingerprint("f.java", "security", anchor, List.of(anchor));
    String fpOtherFile =
        FingerprintCalculator.fingerprint("g.java", "bug", anchor, List.of(anchor));
    assertThat(fpBug).isNotEqualTo(fpSec);
    assertThat(fpBug).isNotEqualTo(fpOtherFile);
  }

  @Test
  void whitespaceStrippingMakesAnchorRobust() {
    // 锚行去首尾空白：缩进漂移不改指纹（02§4.1 anchor_line_content_hash 定义）
    String fp1 = FingerprintCalculator.fingerprint("f.java", "bug", "  return x;  ", List.of("x"));
    String fp2 = FingerprintCalculator.fingerprint("f.java", "bug", "return x;", List.of(" x "));
    assertThat(fp1).isEqualTo(fp2);
  }

  @Test
  void shortContextDetermined() {
    // 文件头部锚行不足 ±2 行：联合哈希仍确定
    String fp1 =
        FingerprintCalculator.fingerprint("f.java", "bug", "package a;", List.of("package a;"));
    String fp2 =
        FingerprintCalculator.fingerprint("f.java", "bug", "package a;", List.of("package a;"));
    assertThat(fp1).isEqualTo(fp2);
  }
}
