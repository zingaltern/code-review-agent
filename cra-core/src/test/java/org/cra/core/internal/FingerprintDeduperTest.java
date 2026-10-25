package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.cra.common.git.LedgerFindingMarker;
import org.junit.jupiter.api.Test;

/**
 * 滑窗去重三连漂移单测（07§2 验收 1、08§5 场景 8/9、02§4.1）：上方插行→抑制、改锚行 ±2 内非锚行→滑窗
 * 命中抑制、改锚行本身→判新问题（possible_duplicate 入 trace、不抑制）。
 */
class FingerprintDeduperTest {

  private static final String FILE = "src/Foo.java";
  private static final String CATEGORY = "bug";
  private static final String ANCHOR = "return items.get(items.size() - 1);";

  /** 旧文件：锚行在第 3 行，上下文 a/b/d/e。 */
  private static final List<String> OLD_LINES = List.of("a", "b", ANCHOR, "d", "e");

  private static LedgerFindingMarker marker(int line) {
    String fp =
        FingerprintCalculator.fingerprint(
            FILE, CATEGORY, ANCHOR, List.of("a", "b", ANCHOR, "d", "e"));
    return new LedgerFindingMarker(fp, FILE, line, CATEGORY, "f_old", "run_old", "abc1234", 42L);
  }

  private static FingerprintDeduper.Decision evaluate(int line, List<String> newLines) {
    Function<LedgerFindingMarker, Optional<String>> oldHash =
        m ->
            Optional.of(
                FingerprintCalculator.sha256(FingerprintDeduper.lineAt(OLD_LINES, m.line())));
    return FingerprintDeduper.evaluate(FILE, CATEGORY, line, newLines, List.of(marker(3)), oldHash);
  }

  @Test
  void exactHitWhenContentUnchanged() {
    var decision = evaluate(3, OLD_LINES);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.EXACT);
    assertThat(decision.matched()).isPresent();
    assertThat(decision.possibleDuplicate()).isFalse();
  }

  @Test
  void tenLinesInsertedDirectlyAboveStillSuppressed() {
    // 漂移 1：紧邻锚行上方插 10 行——锚行内容不变；exact 因上下文含插入行而失配（完整指纹含 ±2 上下文），
    // 滑窗以锚行内容哈希命中（02§4.1：上方插行不改变行内容）
    List<String> inserted = List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "i10");
    List<String> newLines =
        List.of(
            "a", "b", "i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "i10", ANCHOR, "d",
            "e");
    var decision = evaluate(13, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.SLIDING_WINDOW);
  }

  @Test
  void neighborLineWithinTwoEditedStillSuppressed() {
    // 漂移 2：改锚行 ±2 行内的非锚行（b → b'）——锚行内容不变 → 滑窗命中抑制
    List<String> newLines = List.of("a", "b-edited", ANCHOR, "d", "e");
    var decision = evaluate(3, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.SLIDING_WINDOW);
  }

  @Test
  void anchorItselfChangedRepublishesWithPossibleDuplicate() {
    // 漂移 3：改锚行本身——内容已变判新问题重发；同文件同类别行距 ≤5 → possible_duplicate 入 trace
    List<String> newLines = List.of("a", "b", "return items.get(items.size());", "d", "e");
    var decision = evaluate(3, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isTrue();
    assertThat(decision.matched()).isEmpty();
  }

  @Test
  void anchorMovedFarAwayIsPlainNewFinding() {
    // 同文件同类别但行距 >5 且内容已变（新代码不含旧锚行内容）：无 possible_duplicate 标记
    List<String> newLines = List.of("x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10");
    var decision = evaluate(10, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isFalse();
  }

  @Test
  void slidingWindowFindsAnchorWithinFiveLines() {
    // 锚行下移 4 行（行区间首行选取偏移类漂移），exact 因上下文失配 → 滑窗在 ±5 内命中
    List<String> newLines = List.of("a", "b", "c", "d", ANCHOR, "e", "f", "g", "h");
    var decision = evaluate(5, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.SLIDING_WINDOW);
  }

  @Test
  void sameAnchorContentBeyondRadiusIsPossibleDuplicateOnly() {
    // 旧锚行内容仍在文件中、但距报告行 >5（如同类模式出现两处、报告的是另一处）：滑窗未命中，
    // 同文件同类别行距 ≤5 → possible_duplicate（不抑制）
    List<String> newLines =
        List.of("a", "b", "x := y;", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", ANCHOR);
    var decision = evaluate(3, newLines);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isTrue();
  }

  @Test
  void differentCategoryNeverMatches() {
    // 同文件同行同内容但类别不同：指纹键含 category（02§4.1），不算重复
    Function<LedgerFindingMarker, Optional<String>> oldHash =
        m -> Optional.of(FingerprintCalculator.sha256(ANCHOR));
    var decision =
        FingerprintDeduper.evaluate(FILE, "security", 3, OLD_LINES, List.of(marker(3)), oldHash);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isFalse();
  }

  @Test
  void oldAnchorUnavailableSkipsSlidingButKeepsPossibleDuplicate() {
    // 旧内容不可得（force-push/文件删除）：该标记不参与滑窗，行距 ≤5 仍记 possible_duplicate
    Function<LedgerFindingMarker, Optional<String>> oldHash = m -> Optional.empty();
    List<String> newLines = List.of("a", "b", "totally different", "d", "e");
    var decision =
        FingerprintDeduper.evaluate(FILE, CATEGORY, 3, newLines, List.of(marker(3)), oldHash);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isTrue();
  }

  @Test
  void markerOfOtherFileIgnored() {
    Function<LedgerFindingMarker, Optional<String>> oldHash =
        m -> Optional.of(FingerprintCalculator.sha256(ANCHOR));
    LedgerFindingMarker other =
        new LedgerFindingMarker(
            marker(3).fingerprint(),
            "src/Bar.java",
            3,
            CATEGORY,
            "f_old",
            "run_old",
            "abc1234",
            1L);
    var decision =
        FingerprintDeduper.evaluate(FILE, CATEGORY, 3, OLD_LINES, List.of(other), oldHash);
    assertThat(decision.match()).isEqualTo(FingerprintDeduper.Match.NONE);
    assertThat(decision.possibleDuplicate()).isFalse();
  }

  @Test
  void lineAtAndContextWindowMirrorPublisherSemantics() {
    // 越界锚行 → 空串；上下文窗口 = 锚行 ±2（行不足截取可用部分）——与 ReviewPipeline.fingerprintOf 一致
    assertThat(FingerprintDeduper.lineAt(OLD_LINES, 0)).isEmpty();
    assertThat(FingerprintDeduper.lineAt(OLD_LINES, 99)).isEmpty();
    assertThat(FingerprintDeduper.lineAt(OLD_LINES, 3)).isEqualTo(ANCHOR);
    assertThat(FingerprintDeduper.contextWindow(OLD_LINES, 1)).containsExactly("a", "b", ANCHOR);
    assertThat(FingerprintDeduper.contextWindow(OLD_LINES, 3)).hasSize(5);
  }
}
