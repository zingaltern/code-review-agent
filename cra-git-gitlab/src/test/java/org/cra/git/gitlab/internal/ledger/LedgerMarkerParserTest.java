package org.cra.git.gitlab.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.cra.git.gitlab.internal.ledger.LedgerMarkerParser.MarkerParseResult;
import org.cra.git.gitlab.internal.ledger.LedgerMarkerParser.SuspectReason;
import org.junit.jupiter.api.Test;

/** 账本标记解析器逐规则单测（05§1.3、08§5 场景 3；JaCoCo 100% 分支门禁的承重测试，08§7）。 */
class LedgerMarkerParserTest {

  private static final LedgerMarkerParser PARSER = new LedgerMarkerParser();

  private static String findingMarker() {
    return "<!-- cra:v=2.1 | fp=9f2a1b | file=src/Foo.java | cat=bug | id=f_01hxy "
        + "| run=run_01hxy | commit=abc1234 | line=87 -->";
  }

  private static String runMarker() {
    return "<!-- cra:v=2.1 | run=run_01hxy | commit=abc1234 | status=DONE | findings=5 "
        + "| suppressed=0 -->";
  }

  @Test
  void nullAndBlankAndMarkerFreeBodiesYieldNothing() {
    assertThat(PARSER.parse(null).marker()).isEmpty();
    assertThat(PARSER.parse("").marker()).isEmpty();
    assertThat(PARSER.parse("   \n ").marker()).isEmpty();
    assertThat(PARSER.parse("普通评论正文，没有标记").marker()).isEmpty();
    // 非 cra: 的 HTML 注释不算标记
    assertThat(PARSER.parse("正文\n<!-- 其他注释 -->").marker()).isEmpty();
  }

  @Test
  void validFindingMarkerAtEnd() {
    var out = PARSER.parse("finding 正文 src/Foo.java:87\n" + findingMarker());
    assertThat(out.suspects()).isEmpty();
    MarkerParseResult.FindingMark f = (MarkerParseResult.FindingMark) out.marker().orElseThrow();
    assertThat(f.fingerprint()).isEqualTo("9f2a1b");
    assertThat(f.file()).isEqualTo("src/Foo.java");
    assertThat(f.line()).isEqualTo(87);
    assertThat(f.category()).isEqualTo("bug");
    assertThat(f.commitSha()).isEqualTo("abc1234");
  }

  @Test
  void validMultilineMarkerWithLeadingSpaces() {
    // 05§1.3 展示形态：字段换行 + 竖线前导空格
    String body =
        """
        **[Important] 越界** — `src/Foo.java:87`
        …正文…
        <!-- cra:v=2.1 | fp=9f2a1b
             | file=src/Foo.java | cat=bug | id=f_01hxy | run=run_01hxy
             | commit=abc1234 | line=87 -->""";
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void validRunMarkerAtEnd() {
    var out = PARSER.parse("Summary 正文…\n" + runMarker());
    assertThat(out.suspects()).isEmpty();
    MarkerParseResult.RunMark r = (MarkerParseResult.RunMark) out.marker().orElseThrow();
    assertThat(r.runId()).isEqualTo("run_01hxy");
    assertThat(r.status()).isEqualTo("DONE");
    assertThat(r.findings()).isEqualTo(5);
    assertThat(r.suppressed()).isZero();
  }

  @Test
  void trailingWhitespaceAfterMarkerStillValid() {
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker() + "  \n ");
    assertThat(out.marker()).isPresent();
    assertThat(out.suspects()).isEmpty();
  }

  @Test
  void markerInMidBodyIsIgnoredAndCountedSuspect() {
    // 08§5 场景 3：正文中间的伪造标记——忽略 + LEDGER_SUSPECT
    var out =
        PARSER.parse(
            "前文 <!-- cra:v=2.1 | fp=aa | file=x | cat=bug | id=f_1 | run=run_1 "
                + "| commit=abc1234 | line=1 --> 后文");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.MID_BODY);
  }

  @Test
  void midBodyMarkerPlusValidEndMarkerCountsOnlyMidAsSuspect() {
    String body =
        "前文 <!-- cra:v=2.1 | run=run_x | commit=abc1234 | status=DONE | findings=0 "
            + "| suppressed=0 --> 中文 src/Foo.java\n"
            + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.marker()).isPresent();
    assertThat(out.suspects()).containsExactly(SuspectReason.MID_BODY);
  }

  @Test
  void markerInsideCodeFenceIsIgnoredAndCountedSuspect() {
    // 08§5 场景 3：代码块内的伪造标记（body 以 ``` 结尾，不构成末位标记）
    String body = "```xml\n" + findingMarker() + "\n```";
    var out = PARSER.parse(body);
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.MID_BODY);
  }

  @Test
  void endMarkerAfterOddFenceCountIsInFenceSuspect() {
    String body = "```未闭合\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.INSIDE_FENCE);
  }

  @Test
  void sixBacktickFencePairBeforeEndMarkerParses() {
    // 一串 ≥3 连续反引号 = 一个定界符：6 反引号开/闭围栏计 2，末位标记在围栏之外
    String body = "``````\n代码\n``````\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void mixedLengthFencePairBeforeEndMarkerParses() {
    // 旧实现按 3 个一组计数（1+2=3 奇数）误判 INSIDE_FENCE；修复后 1+1=2
    String body = "```java\n代码\n``````\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void unclosedSixBacktickFenceStillRejectsEndMarker() {
    // 反向保护：未闭合的 6 反引号围栏仍须判为围栏内（旧实现计 2 偶数会漏判）
    String body = "``````\n代码\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.INSIDE_FENCE);
  }

  @Test
  void inlineTripleBackticksDoNotToggleFenceParity() {
    // 行中出现的 ``` 不构成围栏，不影响末位标记判定
    String body = "正文含行内 ``` 代码片段\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void indentedFenceDelimitersStillCount() {
    // ≤3 空格缩进的围栏照常计数：成对 → 偶数 → 标记在围栏外
    String body = "   ```xml\n代码\n   ```\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void fourSpaceIndentedBackticksAreNotFenceDelimiters() {
    // 4 空格缩进是缩进代码块而非围栏，不切换奇偶
    String body = "    ```\n代码\n    ```\nsrc/Foo.java:87\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.suspects()).isEmpty();
    assertThat(out.marker()).isPresent();
  }

  @Test
  void unclosedMarkerIsMidBodySuspect() {
    // 未闭合的 cra: 标记不构成末位标记，按"正文中间"计可疑（规则 1）
    var out = PARSER.parse("正文 src/Foo.java\n<!-- cra:v=2.1 | fp=9f2a1b ");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.MID_BODY);
  }

  @Test
  void fieldWithoutEqualsIsMalformed() {
    var out = PARSER.parse("正文 src/Foo.java\n<!-- cra:v=2.1 | 不是一个字段 -->");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.MALFORMED_FIELD);
  }

  @Test
  void firstKeyNotVIsBadOrder() {
    var out = PARSER.parse("正文 src/Foo.java\n<!-- cra:fp=9f2a1b | v=2.1 -->");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_FIELD_ORDER);
  }

  @Test
  void unknownVersionIsSuspect() {
    // 08§5 场景 3：版本非法
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("v=2.1", "v=9.9"));
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VERSION);
  }

  @Test
  void secondKeyNotFpOrRunIsUnknownType() {
    var out = PARSER.parse("正文\n<!-- cra:v=2.1 | whatever=1 -->");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.UNKNOWN_TYPE);
  }

  @Test
  void singleFieldMarkerIsUnknownType() {
    // 只有 v 一个字段（keys.size() == 1 的类型探测分支）
    var out = PARSER.parse("正文\n<!-- cra:v=2.1 -->");
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.UNKNOWN_TYPE);
  }

  @Test
  void wrongFieldOrderIsSuspect() {
    // 08§5 场景 3：字段序错（file 与 cat 互换，第二键仍为 fp，字段序列与约定不符）
    var out =
        PARSER.parse(
            "正文 src/Foo.java\n"
                + findingMarker()
                    .replace("| file=src/Foo.java | cat=bug", "| cat=bug | file=src/Foo.java"));
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_FIELD_ORDER);
  }

  @Test
  void missingFieldIsSuspect() {
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace(" | cat=bug", ""));
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_FIELD_ORDER);
  }

  // ---------- BAD_VALUE 的每个 || 短路分支 ----------

  @Test
  void badFingerprintIsSuspect() {
    var out =
        PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("fp=9f2a1b", "fp=NOT_HEX"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void fingerprintTooLongIsSuspect() {
    var out =
        PARSER.parse(
            "正文 src/Foo.java\n" + findingMarker().replace("fp=9f2a1b", "fp=" + "a".repeat(65)));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void emptyFileIsSuspect() {
    var out = PARSER.parse("正文\n" + findingMarker().replace("file=src/Foo.java", "file="));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void unknownCategoryIsSuspect() {
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("cat=bug", "cat=hack"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void badFindingIdPrefixIsSuspect() {
    var out =
        PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("id=f_01hxy", "id=x_01hxy"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void badRunIdPrefixIsSuspect() {
    var out =
        PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("run=run_01hxy", "run=nope"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void badCommitIsSuspect() {
    var out =
        PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("commit=abc1234", "commit=zzz"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void nonNumericLineIsSuspect() {
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("line=87", "line=L87"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void overflowingLineIsSuspect() {
    var out =
        PARSER.parse(
            "正文 src/Foo.java\n" + findingMarker().replace("line=87", "line=99999999999999"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void zeroLineIsSuspect() {
    var out = PARSER.parse("正文 src/Foo.java\n" + findingMarker().replace("line=87", "line=0"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void fileMismatchWithBodyIsSuspect() {
    // 08§5 场景 3：file 不一致（正文不含该路径）
    var out = PARSER.parse("正文完全无关\n" + findingMarker());
    assertThat(out.marker()).isEmpty();
    assertThat(out.suspects()).containsExactly(SuspectReason.FILE_MISMATCH);
  }

  // ---------- run 标记 BAD_VALUE 分支 ----------

  @Test
  void runMarkerBlankRunIdIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("run=run_01hxy", "run="));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void runMarkerBadCommitIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("commit=abc1234", "commit=1"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void runMarkerBadStatusIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("status=DONE", "status=RUNNING"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void runMarkerNegativeFindingsIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("findings=5", "findings=-1"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void runMarkerNonNumericFindingsIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("findings=5", "findings=五"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void runMarkerNonNumericSuppressedIsSuspect() {
    var out = PARSER.parse("x\n" + runMarker().replace("suppressed=0", "suppressed=x"));
    assertThat(out.suspects()).containsExactly(SuspectReason.BAD_VALUE);
  }

  @Test
  void degradedRunMarkerIsValid() {
    // 02§4.1：DEGRADED 也是完成标记
    var out = PARSER.parse("Summary…\n" + runMarker().replace("status=DONE", "status=DEGRADED"));
    assertThat(out.marker()).isPresent();
    assertThat(out.suspects()).isEmpty();
  }

  @Test
  void crlfLineEndingsDoNotBreakFencePairDetection() {
    String body = "``````\r\n代码\r\n``````\r\nsrc/Foo.java:88\r\n" + findingMarker() + "\r\n";
    var out = PARSER.parse(body);
    assertThat(out.marker()).isPresent();
    assertThat(out.suspects()).isEmpty();
  }

  @Test
  void indentedFenceWithThreeSpacesStillCountsAsFence() {
    String body = "   ```\n代码\n   ```\nsrc/Foo.java:89\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.marker()).isPresent();
  }

  @Test
  void indentedFenceWithFourSpacesIsIndentedCodeNotFence() {
    String body = "    ```\n代码\nsrc/Foo.java:90\n" + findingMarker();
    var out = PARSER.parse(body);
    assertThat(out.marker()).isPresent();
  }
}
