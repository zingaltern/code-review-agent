package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 用例断言规格（06§3 expected_findings.json，格式 v1）。
 *
 * @param formatVersion 格式版本，当前恒为 1
 * @param expected 期望发现列表（clean 集为空列表）
 * @param negativeBaits 负向 bait（幻觉/对抗用例），bad/clean 通常为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpectedFindings(
    @JsonProperty("format_version") int formatVersion,
    @JsonProperty("expected") List<ExpectedFinding> expected,
    @JsonProperty("negative_baits") List<NegativeBait> negativeBaits) {

  public ExpectedFindings {
    if (formatVersion != FixtureMeta.FORMAT_VERSION) {
      throw new IllegalArgumentException(
          "expected_findings format_version=" + formatVersion + " 不受支持");
    }
    expected = List.copyOf(expected == null ? List.of() : expected);
    negativeBaits = List.copyOf(negativeBaits == null ? List.of() : negativeBaits);
  }
}
