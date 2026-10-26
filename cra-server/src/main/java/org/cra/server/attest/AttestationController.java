package org.cra.server.attest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告签发端点（04§2.4 M4b）：POST /api/v1/reports/attest，原始报告 JSON → 行式签名信封（text/plain）。
 *
 * <p>受 {@code ApiTokenFilter} 保护（Bearer token）；仅服务端持有 HMAC 密钥，CI 侧用同一密钥（secret）验签。
 */
@RestController
public final class AttestationController {

  private final AttestationService attestationService;

  /**
   * @param attestationService 签发服务
   */
  public AttestationController(AttestationService attestationService) {
    this.attestationService = attestationService;
  }

  /**
   * 签发报告。
   *
   * @param body 原始报告 JSON
   * @return 签名信封（text/plain）或错误文本
   */
  @PostMapping(
      path = "/api/v1/reports/attest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> attest(@RequestBody byte[] body) {
    try {
      AttestationService.Result result = attestationService.attest(body, "api-token");
      return ResponseEntity.ok()
          .header("X-CRA-Attestation-Id", result.attestationId())
          .header("X-CRA-Key-Id", result.keyId())
          .header("X-CRA-Report-Sha256", result.reportSha256())
          .body(result.envelopeText());
    } catch (AttestationRejectedException e) {
      return ResponseEntity.status(HttpStatus.valueOf(e.status()))
          .contentType(MediaType.TEXT_PLAIN)
          .body("error: " + e.getMessage() + "\n");
    }
  }
}
