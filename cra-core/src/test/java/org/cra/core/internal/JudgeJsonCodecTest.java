package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** JudgeAgent 输出契约（03§4.4：一次性终裁，仅 CONFIRMED/REJECTED 二值）。 */
class JudgeJsonCodecTest {

  @Test
  void parsesConfirmedJudgement() {
    JudgeJsonCodec.ParseResult result =
        JudgeJsonCodec.parse(
            "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"L4 证据链成立\"}}");
    assertThat(result.errors()).isEmpty();
    assertThat(result.judgement().verdict()).isEqualTo(JudgeJsonCodec.JudgeVerdict.CONFIRMED);
  }

  @Test
  void parsesRejectedJudgement() {
    JudgeJsonCodec.ParseResult result =
        JudgeJsonCodec.parse("{\"judgement\":{\"verdict\":\"REJECTED\",\"rationale\":\"反证成立\"}}");
    assertThat(result.errors()).isEmpty();
    assertThat(result.judgement().verdict()).isEqualTo(JudgeJsonCodec.JudgeVerdict.REJECTED);
  }

  @Test
  void middleGroundIsInvalid() {
    // 不折中：INSUFFICIENT/其他第三态一律 schema 错误
    assertThat(
            JudgeJsonCodec.parse(
                    "{\"judgement\":{\"verdict\":\"INSUFFICIENT_EVIDENCE\",\"rationale\":\"x\"}}")
                .errors())
        .isNotEmpty();
  }

  @Test
  void missingFieldsAreSchemaErrors() {
    assertThat(JudgeJsonCodec.parse("{}").errors()).isNotEmpty();
    assertThat(JudgeJsonCodec.parse("{\"judgement\":{}}").errors()).isNotEmpty();
    assertThat(JudgeJsonCodec.parse("{\"judgement\":{\"verdict\":\"CONFIRMED\"}}").errors())
        .isNotEmpty();
    assertThat(JudgeJsonCodec.parse("not json").errors()).isNotEmpty();
  }
}
