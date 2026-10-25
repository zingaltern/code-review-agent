package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 账本标记宽松解析（eval 观测面；05§1.3 格式）。 */
class EvalMarkerParserTest {

  private static final String FINDING_BODY =
      """
      **[IMPORTANT] 空列表越界** — `src/Foo.java:87`

      size()-1 返回 -1。

      <!-- cra:v=2.1 | fp=9f2a | file=src/Foo.java | cat=bug | id=f_01 | run=run_1 | commit=abc1234 | line=87 -->
      """;

  @Test
  void parsesFindingMarkerFields() {
    List<EvalMarkerParser.FindingMarker> markers =
        EvalMarkerParser.parseFindingMarkers(FINDING_BODY);
    assertThat(markers).hasSize(1);
    EvalMarkerParser.FindingMarker m = markers.get(0);
    assertThat(m.file()).isEqualTo("src/Foo.java");
    assertThat(m.category()).isEqualTo("bug");
    assertThat(m.line()).isEqualTo(87);
    assertThat(m.findingId()).isEqualTo("f_01");
    assertThat(m.runId()).isEqualTo("run_1");
    assertThat(m.fingerprint()).isEqualTo("9f2a");
  }

  @Test
  void parsesRunMarkerFromSummaryEnd() {
    String body =
        """
        ## cra 评审 Summary（run_2）

        - 指纹去重抑制 0 条

        <!-- cra:v=2.1 | run=run_old | commit=old | status=DONE | findings=1 | suppressed=0 -->

        <!-- cra:v=2.1 | run=run_2 | commit=deadbee | status=DEGRADED | findings=3 | suppressed=1 -->
        """;
    EvalMarkerParser.RunMarker marker = EvalMarkerParser.parseRunMarker(body);
    assertThat(marker).isNotNull();
    assertThat(marker.runId()).isEqualTo("run_2"); // 末位优先（05§1.3 规则 1）
    assertThat(marker.status()).isEqualTo("DEGRADED");
    assertThat(marker.findings()).isEqualTo(3);
  }

  @Test
  void noMarkerYieldsEmptyAndNull() {
    assertThat(EvalMarkerParser.parseFindingMarkers("plain body")).isEmpty();
    assertThat(EvalMarkerParser.parseRunMarker("plain body")).isNull();
    assertThat(EvalMarkerParser.parseFindingMarkers(null)).isEmpty();
    assertThat(EvalMarkerParser.parseRunMarker(null)).isNull();
  }

  @Test
  void malformedFieldsAreSkipped() {
    String body =
        "<!-- cra:v=2.1 | file=Foo.java | cat=bug | line=NaN -->\n"
            + "<!-- cra:v=2.1 | file=Bar.java | cat=bug | line=5 | id=f_9 -->";
    List<EvalMarkerParser.FindingMarker> markers = EvalMarkerParser.parseFindingMarkers(body);
    assertThat(markers).hasSize(1);
    assertThat(markers.get(0).file()).isEqualTo("Bar.java");
  }
}
