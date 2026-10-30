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

  @Test
  void parsesStructuredRefutationForRefutedVerdict() {
    // ADR-0015：bug 类 REFUTED 的结构化反证（kind/anchor/quote/reason）
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"REFUTED","rationale":"L3 已有防护",
              "refutation":{"kind":"GUARD_EXISTS","anchor":"src/Foo.java:3",
                "quote":"if (x == null) return;","reason":"上游空值早退"}}]}""");
    assertThat(result.errors()).isEmpty();
    VerdictJsonCodec.Refutation refutation = result.verdicts().get("f_1").refutation();
    assertThat(refutation).isNotNull();
    assertThat(refutation.kind()).isEqualTo(VerdictJsonCodec.RefutationKind.GUARD_EXISTS);
    assertThat(refutation.anchor()).isEqualTo("src/Foo.java:3");
    assertThat(refutation.quote()).isEqualTo("if (x == null) return;");
    assertThat(refutation.usable()).isTrue();
  }

  @Test
  void malformedRefutationDoesNotFailBatchButIsMarked() {
    // 结构不合法不判 schema 错（避免一个附加字段拖垮整批），交机械校验按缺失降级
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"REFUTED","rationale":"x",
              "refutation":{"kind":"NOT_A_KIND","anchor":"src/Foo.java:1"}}]}""");
    assertThat(result.errors()).isEmpty();
    VerdictJsonCodec.Refutation refutation = result.verdicts().get("f_1").refutation();
    assertThat(refutation).isNotNull();
    assertThat(refutation.usable()).isFalse();
    assertThat(refutation.malformed()).isTrue();
  }

  @Test
  void refutationAbsentIsAllowedAtParseLevel() {
    // 兼容旧输出：解析层不强制（bug 类的强制由 RefuteEvidenceGate 执行）
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            """
            {"verdicts":[{"finding_id":"f_1","verdict":"REFUTED","rationale":"x"}]}""");
    assertThat(result.errors()).isEmpty();
    assertThat(result.verdicts().get("f_1").refutation()).isNull();
  }

  @Test
  void blankFindingIdIsSchemaError() {
    VerdictJsonCodec.ParseResult result =
        VerdictJsonCodec.parse(
            "{\"verdicts\":[{\"finding_id\":\"  \",\"verdict\":\"CONFIRMED\",\"rationale\":\"L3\"}]}");
    assertThat(result.verdicts()).isEmpty();
    assertThat(result.errors()).anyMatch(e -> e.contains("finding_id"));
  }
}
