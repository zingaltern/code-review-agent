package org.cra.server.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * GitHub webhook 签名校验（X-Hub-Signature-256 = sha256=HMAC-SHA256(secret, raw body)）。
 *
 * <p>验签在解析 JSON 之前执行（不处理未签名输入）；比较用 {@link MessageDigest#isEqual}（常数时间），头部格式非法即拒绝。
 */
public final class WebhookSignatureVerifier {

  private static final String PREFIX = "sha256=";

  private final byte[] secret;

  /**
   * @param secret webhook 密钥（CRA_SERVER_WEBHOOK_SECRET）
   */
  public WebhookSignatureVerifier(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("webhook 密钥不能为空");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 校验签名。
   *
   * @param body 原始请求体
   * @param signatureHeader X-Hub-Signature-256 头（可空）
   * @return true = 合法
   */
  public boolean verify(byte[] body, String signatureHeader) {
    if (body == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
      return false;
    }
    String provided = signatureHeader.substring(PREFIX.length()).strip();
    if (provided.length() != 64 || !provided.matches("[0-9a-fA-F]+")) {
      return false;
    }
    byte[] expected = hmac(body);
    byte[] actual = HexFormat.of().parseHex(provided);
    return MessageDigest.isEqual(expected, actual);
  }

  /**
   * 计算签名头（测试与本地模拟 webhook 用）。
   *
   * @param body 原始请求体
   * @param secret 密钥
   * @return sha256=&lt;hex&gt;
   */
  public static String signatureHeader(byte[] body, String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("webhook 密钥不能为空");
    }
    return PREFIX
        + HexFormat.of().formatHex(hmacRaw(body, secret.getBytes(StandardCharsets.UTF_8)));
  }

  private byte[] hmac(byte[] body) {
    return hmacRaw(body, secret);
  }

  private static byte[] hmacRaw(byte[] body, byte[] key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(body);
    } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 不可用", e);
    }
  }
}
