package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** VerifierAgent 输出契约（04§6：schema 校验、携错重试、禁止正则修 JSON）。 */
class VerdictJsonCodecTest {

  @Test
  void parsesThreeValuedVerdictsAndFollowup() {
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[
              {"finding_id":"f_1","verdict":"CONFIRMED","rationale":"L3 独立定位"},
              {"finding_id":"f_2","verdict":"REFUTED","rationale":"L3 已有防护"},
              {"finding_id":"f_3","verdict":"INSUFFICIENT_EVIDENCE","rationale":"需补查",
               "followup_file":"src/Helper.java"}]}""");
    assertThat(result.errors()).isEmpty();
    assertThat(result.verdicts()).hasSize(3);
    assertThat(result.verdicts().get("f_1").verdict())
        .isEqualTo(VerdictJsonCodec.VerifierVerdict.CONFIRMED);
    assertThat(result.verdicts().get("f_2").verdict())
        .isEqualTo(VerdictJsonCodec.VerifierVerdict.REFUTED);
    assertThat(result.followups()).containsEntry("f_3", "src/Helper.java");
  }

  @Test
  void missingRationaleIsSchemaError() {
    // 判定要求附理由——缺 rationale 触发携错重试，不放宽 schema
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"CONFIRMED"}]}""");
    assertThat(result.errors()).isNotEmpty();
    assertThat(result.errors().get(0)).contains("rationale");
  }

  @Test
  void invalidVerdictEnumIsSchemaError() {
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"MAYBE","rationale":"x"}]}""");
    assertThat(result.errors()).isNotEmpty();
    assertThat(result.errors().get(0)).contains("非法 verdict");
  }

  @Test
  void unavailableIsNotAValidOutputValue() {
    // UNAVAILABLE 是编排器语义值（验证不可得），验证者不得直接输出
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"UNAVAILABLE","rationale":"x"}]}""");
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void duplicateFindingIdIsSchemaError() {
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[
              {"finding_id":"f_1","verdict":"CONFIRMED","rationale":"a"},
              {"finding_id":"f_1","verdict":"REFUTED","rationale":"b"}]}""");
    assertThat(result.errors()).isNotEmpty();
    assertThat(result.errors().get(0)).contains("重复");
  }

  @Test
  void emptyOrMissingVerdictsArrayIsSchemaError() {
    assertThat(VerdictJsonCodec.parse("{\"verdicts\":[]}").errors()).isNotEmpty();
    assertThat(VerdictJsonCodec.parse("{\"other\":1}").errors()).isNotEmpty();
    assertThat(VerdictJsonCodec.parse("not json").errors()).isNotEmpty();
  }
}
