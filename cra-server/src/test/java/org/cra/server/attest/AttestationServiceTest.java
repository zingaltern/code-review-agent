package org.cra.server.attest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.attest.ReportSignature;
import org.junit.jupiter.api.Test;

/** 服务端报告签名（04§2.4 M4b）：签发/验签、篡改拒绝、已知 commit 策略。 */
class AttestationServiceTest {

  private static final String KEY = "server-signing-key";
  private static final byte[] REPORT =
      "{\"commit\":\"abc1234\",\"tier\":\"l2\"}".getBytes(StandardCharsets.UTF_8);

  private static final class CapturingStore implements AttestationStore {
    private final List<AttestationRecord> records = new ArrayList<>();

    @Override
    public void recordIssued(AttestationRecord attestation) {
      records.add(attestation);
    }
  }

  private static AttestationService service(boolean known, CapturingStore store) {
    return new AttestationService(KEY, "key-v1", true, (repo, commit) -> known, store);
  }

  @Test
  void signsAndVerifiesReport() {
    CapturingStore store = new CapturingStore();
    AttestationService.Result result =
        service(true, store).attest(REPORT, "owner/repo", "api-token");

    ReportSignature.Envelope envelope = ReportSignature.parse(result.envelopeText());
    assertThat(
            ReportSignature.verify(
                KEY.getBytes(StandardCharsets.UTF_8), envelope, ReportSignature.sha256(REPORT)))
        .isTrue();
    assertThat(envelope.repo()).isEqualTo("owner/repo");
    assertThat(envelope.commit()).isEqualTo("abc1234");
    assertThat(envelope.tier()).isEqualTo("l2");
    assertThat(envelope.keyId()).isEqualTo("key-v1");
    assertThat(store.records).hasSize(1);
    assertThat(store.records.get(0).attestationId()).isEqualTo(result.attestationId());
    assertThat(store.records.get(0).repo()).isEqualTo("owner/repo");
  }

  @Test
  void refusesMissingRepoWhenPolicyEnabled() {
    assertThatThrownBy(() -> service(true, new CapturingStore()).attest(REPORT, "", "api-token"))
        .isInstanceOf(AttestationRejectedException.class)
        .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(409));
  }

  @Test
  void refusesUnknownCommitWhenPolicyEnabled() {
    assertThatThrownBy(
            () -> service(false, new CapturingStore()).attest(REPORT, "owner/repo", "api-token"))
        .isInstanceOf(AttestationRejectedException.class)
        .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(409));
  }

  @Test
  void refusesMissingCommitWhenPolicyEnabled() {
    byte[] noCommit = "{\"tier\":\"l2\"}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> service(true, new CapturingStore()).attest(noCommit, "owner/repo", "api-token"))
        .isInstanceOf(AttestationRejectedException.class)
        .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(409));
  }

  @Test
  void permissivePolicySignsUnknownCommit() {
    AttestationService permissive =
        new AttestationService(KEY, "key-v1", false, (repo, commit) -> false, new CapturingStore());
    AttestationService.Result result = permissive.attest(REPORT, "owner/repo", "api-token");
    assertThat(ReportSignature.parse(result.envelopeText()).commit()).isEqualTo("abc1234");
  }

  @Test
  void permissivePolicyStillRefusesBlankAndInvalidRepoWith409() {
    // repo 校验在 requireKnownCommit 之外：宽松策略下缺失/非法同样走 409 拒绝路径，
    // 不再落到底层 Envelope 不变量抛 IllegalArgumentException（500）
    AttestationService permissive =
        new AttestationService(KEY, "key-v1", false, (repo, commit) -> false, new CapturingStore());
    for (String repo : new String[] {"", "   ", null, "not-a-repo", "owner/", "a/b/c"}) {
      assertThatThrownBy(() -> permissive.attest(REPORT, repo, "api-token"))
          .isInstanceOf(AttestationRejectedException.class)
          .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(409));
    }
  }

  @Test
  void invalidRepoFormatIsRejectedBeforeKnownCommitCheck() {
    // 形式非法在入口即拒：knownCommitChecker 不应被调用（checker 返回 true 也不能放行）
    AttestationService strictWithKnownCommit =
        new AttestationService(KEY, "key-v1", true, (repo, commit) -> true, new CapturingStore());
    assertThatThrownBy(() -> strictWithKnownCommit.attest(REPORT, "owner//name", "api-token"))
        .isInstanceOf(AttestationRejectedException.class)
        .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(409));
  }

  @Test
  void rejectsInvalidJson() {
    assertThatThrownBy(
            () ->
                service(true, new CapturingStore())
                    .attest("not-json".getBytes(StandardCharsets.UTF_8), "owner/repo", "api-token"))
        .isInstanceOf(AttestationRejectedException.class)
        .satisfies(e -> assertThat(((AttestationRejectedException) e).status()).isEqualTo(400));
  }

  @Test
  void tamperedReportFailsVerification() {
    AttestationService.Result result =
        service(true, new CapturingStore()).attest(REPORT, "owner/repo", "api-token");
    ReportSignature.Envelope envelope = ReportSignature.parse(result.envelopeText());
    byte[] tampered =
        (new String(REPORT, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
    assertThat(
            ReportSignature.verify(
                KEY.getBytes(StandardCharsets.UTF_8), envelope, ReportSignature.sha256(tampered)))
        .isFalse();
  }
}
