package org.cra.server.attest;

import java.time.Instant;

/**
 * 报告签发记录（审计面：谁在何时为哪个摘要签发；04§2.4 M4b）。
 *
 * @param attestationId 签发标识（att_…）
 * @param reportSha256 报告文件 SHA-256
 * @param commitSha 报告绑定的 commit
 * @param tier 报告档位
 * @param keyId 密钥标识
 * @param issuedAt 签发时间
 * @param requestedBy 请求方标识（API token 语义）
 * @param signature HMAC 签名
 */
public record AttestationRecord(
    String attestationId,
    String reportSha256,
    String commitSha,
    String tier,
    String keyId,
    Instant issuedAt,
    String requestedBy,
    String signature) {}
