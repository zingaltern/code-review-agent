package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** webhook 签名校验：合法/非法/缺头/格式错（04§2.4、02§5）。 */
class WebhookSignatureVerifierTest {

  private static final String SECRET = "webhook-secret";
  private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

  @Test
  void acceptsValidSignature() {
    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
    String header = WebhookSignatureVerifier.signatureHeader(BODY, SECRET);
    assertThat(verifier.verify(BODY, header)).isTrue();
  }

  @Test
  void rejectsWrongBody() {
    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
    String header = WebhookSignatureVerifier.signatureHeader(BODY, SECRET);
    byte[] tampered = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.verify(tampered, header)).isFalse();
  }

  @Test
  void rejectsWrongSecret() {
    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
    String header = WebhookSignatureVerifier.signatureHeader(BODY, "another-secret");
    assertThat(verifier.verify(BODY, header)).isFalse();
  }

  @Test
  void rejectsMissingOrMalformedHeader() {
    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);
    assertThat(verifier.verify(BODY, null)).isFalse();
    assertThat(verifier.verify(BODY, "")).isFalse();
    assertThat(verifier.verify(BODY, "sha256=")).isFalse();
    assertThat(verifier.verify(BODY, "sha1=abcd")).isFalse();
    assertThat(verifier.verify(BODY, "sha256=zzzz")).isFalse();
  }

  @Test
  void rejectsEmptySecretAtConstruction() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(() -> new WebhookSignatureVerifier(" ")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
