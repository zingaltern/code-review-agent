package org.cra.common.attest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 报告签名信封（04§2.4 M4b）：签验往返、篡改拒绝、格式严格性。 */
class ReportSignatureTest {

  private static final byte[] SECRET = "unit-test-signing-key".getBytes(StandardCharsets.UTF_8);
  private static final byte[] REPORT =
      "{\"commit\":\"abc1234\",\"tier\":\"l2\"}".getBytes(StandardCharsets.UTF_8);

  private static ReportSignature.Envelope envelope() {
    return ReportSignature.sign(
        SECRET,
        "test-key-v1",
        ReportSignature.sha256(REPORT),
        "owner/repo",
        "abc1234",
        "l2",
        "2026-10-26T21:00:00Z",
        "att_0001");
  }

  @Test
  void signAndVerifyRoundtrip() {
    ReportSignature.Envelope signed = envelope();
    assertThat(ReportSignature.verify(SECRET, signed, ReportSignature.sha256(REPORT))).isTrue();
    assertThat(ReportSignature.parse(signed.text())).isEqualTo(signed);
  }

  @Test
  void tamperedReportDigestFails() {
    ReportSignature.Envelope signed = envelope();
    byte[] tampered =
        (new String(REPORT, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
    assertThat(ReportSignature.verify(SECRET, signed, ReportSignature.sha256(tampered))).isFalse();
  }

  @Test
  void tamperedEnvelopeFieldFails() {
    ReportSignature.Envelope signed = envelope();
    ReportSignature.Envelope forged =
        new ReportSignature.Envelope(
            signed.keyId(),
            signed.reportSha256(),
            signed.repo(),
            "deadbeef",
            signed.tier(),
            signed.issuedAt(),
            signed.runId(),
            signed.signature());
    assertThat(ReportSignature.verify(SECRET, forged, ReportSignature.sha256(REPORT))).isFalse();
  }

  @Test
  void wrongKeyFails() {
    ReportSignature.Envelope signed = envelope();
    assertThat(
            ReportSignature.verify(
                "other-key".getBytes(StandardCharsets.UTF_8),
                signed,
                ReportSignature.sha256(REPORT)))
        .isFalse();
  }

  @Test
  void parseRejectsWrongScheme() {
    String text = envelope().text().replace(ReportSignature.SCHEME, "cra-report-hmac-sha1-v1");
    assertThatThrownBy(() -> ReportSignature.parse(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("签名方案不支持");
  }

  @Test
  void parseRejectsMissingField() {
    String text = envelope().text().replace("tier:l2\n", "");
    assertThatThrownBy(() -> ReportSignature.parse(text))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptySecretRejected() {
    assertThatThrownBy(
            () ->
                ReportSignature.sign(
                    new byte[0],
                    "k",
                    ReportSignature.sha256(REPORT),
                    "owner/repo",
                    "",
                    "",
                    "t",
                    "r"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("密钥");
  }
}
