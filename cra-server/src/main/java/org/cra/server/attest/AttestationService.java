package org.cra.server.attest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.cra.common.attest.ReportSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端报告签名（04§2.4 M4b）：对报告文件字节做 SHA-256，签发 HMAC-SHA256 信封并落审计表。
 *
 * <p>签发策略（ADR-0008）：默认只对服务端已完成评审的 commit 签名（{@code CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT=true}）——
 * 签名因此绑定"服务端见过并评审过该提交"。这比纯哈希校验强（作者无法伪造服务端密钥），但不等价于服务端执行了评测本身；托管执行是更强的后续形态。
 */
public final class AttestationService {

  private static final Logger LOG = LoggerFactory.getLogger(AttestationService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 签发结果。
   *
   * @param envelopeText 签名信封文本（report.json.sig）
   * @param attestationId 签发标识
   * @param keyId 密钥标识
   * @param reportSha256 报告摘要
   */
  public record Result(
      String envelopeText, String attestationId, String keyId, String reportSha256) {}

  private final byte[] signingKey;
  private final String keyId;
  private final boolean requireKnownCommit;
  private final KnownCommitChecker knownCommitChecker;
  private final AttestationStore attestationStore;

  /**
   * @param signingKey HMAC 密钥（UTF-8）
   * @param keyId 密钥标识
   * @param requireKnownCommit 是否只签服务端已知 commit
   * @param knownCommitChecker 已知 commit 检查
   * @param attestationStore 签发记录落库
   */
  public AttestationService(
      String signingKey,
      String keyId,
      boolean requireKnownCommit,
      KnownCommitChecker knownCommitChecker,
      AttestationStore attestationStore) {
    if (signingKey == null || signingKey.isBlank()) {
      throw new IllegalArgumentException("签名密钥不能为空");
    }
    this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
    this.keyId = keyId;
    this.requireKnownCommit = requireKnownCommit;
    this.knownCommitChecker = knownCommitChecker;
    this.attestationStore = attestationStore;
  }

  /**
   * 签发报告。
   *
   * @param reportBytes 报告文件原始字节
   * @param requestedBy 请求方标识
   * @return 签发结果
   * @throws AttestationRejectedException 报告非法或 commit 未经服务端评审
   */
  public Result attest(byte[] reportBytes, String requestedBy) {
    if (reportBytes == null || reportBytes.length == 0) {
      throw new AttestationRejectedException(400, "报告为空");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(reportBytes);
    } catch (IOException e) {
      throw new AttestationRejectedException(400, "报告不是合法 JSON: " + e.getMessage());
    }
    if (!root.isObject()) {
      throw new AttestationRejectedException(400, "报告必须是 JSON 对象");
    }
    String commit = text(root, "commit");
    String tier = text(root, "tier");
    if (requireKnownCommit) {
      if (commit.isEmpty()) {
        throw new AttestationRejectedException(409, "报告缺 commit 绑定，拒绝签发");
      }
      if (!knownCommitChecker.exists(commit)) {
        throw new AttestationRejectedException(
            409, "commit " + commit + " 未经服务端完成评审（review_run 无 DONE|DEGRADED）");
      }
    }
    String reportSha256 = ReportSignature.sha256(reportBytes);
    String attestationId = "att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    Instant issuedAt = Instant.now();
    ReportSignature.Envelope envelope =
        ReportSignature.sign(
            signingKey, keyId, reportSha256, commit, tier, issuedAt.toString(), attestationId);
    attestationStore.record(
        new AttestationRecord(
            attestationId,
            reportSha256,
            commit,
            tier,
            keyId,
            issuedAt,
            requestedBy == null || requestedBy.isBlank() ? "api-token" : requestedBy,
            envelope.signature()));
    LOG.info(
        "报告已签发: attestation={} sha256={} commit={} tier={} keyId={}",
        attestationId,
        reportSha256,
        commit,
        tier,
        keyId);
    return new Result(envelope.text(), attestationId, keyId, reportSha256);
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value == null || value.isNull() ? "" : value.asText("").strip();
  }
}
