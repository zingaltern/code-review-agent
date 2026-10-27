package org.cra.server.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * GitLab webhook 验签（`X-Gitlab-Token` = 配置在 GitLab webhook 上的 Secret token）。
 *
 * <p>GitLab 的 secret token 是共享密钥的直传，不是 HMAC（与 GitHub 的 `X-Hub-Signature-256` 不同）。比较用 SHA-256 摘要后的
 * {@link MessageDigest#isEqual}：常数时间且不泄露密钥长度。缺失/空头直接拒绝。
 */
public final class GitLabWebhookTokenVerifier {

  private final byte[] expectedDigest;

  /**
   * @param secret GitLab webhook Secret token（CRA_SERVER_WEBHOOK_SECRET）
   */
  public GitLabWebhookTokenVerifier(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("GitLab webhook token 不能为空");
    }
    this.expectedDigest = sha256(secret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 校验 `X-Gitlab-Token`。
   *
   * @param tokenHeader 请求头值（可空）
   * @return true = 合法
   */
  public boolean verify(String tokenHeader) {
    if (tokenHeader == null || tokenHeader.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        expectedDigest, sha256(tokenHeader.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }
}
