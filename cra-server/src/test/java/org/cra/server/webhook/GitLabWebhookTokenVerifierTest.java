package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** GitLab X-Gitlab-Token 验签（ADR-0013）。 */
class GitLabWebhookTokenVerifierTest {

  private final GitLabWebhookTokenVerifier verifier = new GitLabWebhookTokenVerifier("s3cret");

  @Test
  void acceptsExactTokenOnly() {
    assertThat(verifier.verify("s3cret")).isTrue();
    assertThat(verifier.verify("s3cret ")).isFalse();
    assertThat(verifier.verify("S3CRET")).isFalse();
    assertThat(verifier.verify("wrong")).isFalse();
    assertThat(verifier.verify("")).isFalse();
    assertThat(verifier.verify(null)).isFalse();
  }

  @Test
  void rejectsBlankSecretAtConstruction() {
    assertThatThrownBy(() -> new GitLabWebhookTokenVerifier(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GitLabWebhookTokenVerifier(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
