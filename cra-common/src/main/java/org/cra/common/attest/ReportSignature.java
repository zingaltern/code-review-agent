package org.cra.common.attest;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务端报告签名信封（04§2.4 荣誉制消除、M4b）：cra-server 用 HMAC-SHA256 密钥对 L2 评测报告签名，cra-eval verify 用同一密钥验签。
 *
 * <p>签名对象 = 报告文件字节的 SHA-256 + commit/tier 绑定字段；信封是行式文本（key:value），不引入 JSON 解析依赖（cra-common
 * 保持零依赖）。签名只证明"持有服务端密钥者签发了该报告摘要"，不证明报告内容由服务端生成——托管执行是更强的形态，见 ADR-0008 的局限声明。
 *
 * <p>信封格式（行序固定，UTF-8）：
 *
 * <pre>
 * cra-report-hmac-sha256-v2
 * key_id:&lt;服务端密钥标识&gt;
 * report_sha256:&lt;报告文件 SHA-256 小写十六进制&gt;
 * repo:&lt;owner/name&gt;
 * commit:&lt;报告绑定的代码提交，可为空&gt;
 * tier:&lt;l1|l2|l3，可为空&gt;
 * issued_at:&lt;ISO-8601 签发时间&gt;
 * run_id:&lt;服务端签发标识&gt;
 * signature:&lt;HMAC-SHA256 小写十六进制&gt;
 * </pre>
 *
 * <p>HMAC 的输入是前八行（含版本行，至 {@code run_id} 行）以 {@code \n} 连接、末尾无换行；{@code signature} 行不参与。行式格式便于
 * shell/CI 直接 diff 与人工审阅，也避免在 cra-common 引入 Jackson。
 */
public final class ReportSignature {

  /** 签名方案标识（格式变更必须 bump 版本并写 ADR）。 */
  public static final String SCHEME = "cra-report-hmac-sha256-v2";

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int SHA256_HEX_LENGTH = 64;

  /** repo 形式（owner/name；信封与签发端共用同一规则，ADR-0009 决策 1）。 */
  private static final String REPO_FORMAT = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+";

  private ReportSignature() {}

  /**
   * repo 是否为合法的 owner/name 形式（签发端入口校验与 {@link Envelope} 不变量共用同一规则）。
   *
   * @param value 待校验值（先 strip 再判定；null/空白为 false）
   * @return true 若合法
   */
  public static boolean isValidRepo(String value) {
    return value != null && !value.isBlank() && value.strip().matches(REPO_FORMAT);
  }

  /**
   * 签名信封（字段与行式格式一一对应）。
   *
   * @param keyId 服务端密钥标识（轮换时区分密钥）
   * @param reportSha256 报告文件 SHA-256（小写十六进制）
   * @param repo 报告归属仓库（owner/name，v2 新增；多租户签发范围限定）
   * @param commit 报告绑定的代码提交（可为空串）
   * @param tier 报告门禁档位（l1|l2|l3，可为空串）
   * @param issuedAt 签发时间（ISO-8601）
   * @param runId 服务端签发标识
   * @param signature HMAC-SHA256 小写十六进制
   */
  public record Envelope(
      String keyId,
      String reportSha256,
      String repo,
      String commit,
      String tier,
      String issuedAt,
      String runId,
      String signature) {

    public Envelope {
      keyId = requireText(keyId, "keyId");
      reportSha256 = requireSha256(reportSha256, "reportSha256");
      repo = requireRepo(repo);
      commit = requireOptionalSingleLine(commit, "commit");
      tier = requireOptionalSingleLine(tier, "tier");
      issuedAt = requireText(issuedAt, "issuedAt");
      runId = requireText(runId, "runId");
      signature = requireSha256(signature, "signature");
    }

    /**
     * HMAC 输入文本（版本行 + 七个字段行；不含 signature 行，末尾无换行）。
     *
     * @return canonical text
     */
    public String canonicalText() {
      return String.join(
          "\n",
          SCHEME,
          "key_id:" + keyId,
          "report_sha256:" + reportSha256,
          "repo:" + repo,
          "commit:" + commit,
          "tier:" + tier,
          "issued_at:" + issuedAt,
          "run_id:" + runId);
    }

    /**
     * 完整信封文本（canonical + signature 行 + 末尾换行）。
     *
     * @return 可落盘/传输的信封
     */
    public String text() {
      return canonicalText() + "\nsignature:" + signature + "\n";
    }

    /**
     * 必填单行字段：拒绝空值与 CR/LF。
     *
     * @param value 字段值
     * @param field 字段名（错误信息用）
     * @return 去空白后的值
     */
    private static String requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " 不能为空");
      }
      if (value.contains("\n") || value.contains("\r")) {
        throw new IllegalArgumentException(field + " 不得包含换行");
      }
      return value.strip();
    }

    /** 可空单行字段（commit/tier）：空值归一为空串，但同样拒绝 CR/LF——否则信封会多出物理行。 */
    private static String requireOptionalSingleLine(String value, String field) {
      if (value == null || value.isBlank()) {
        return "";
      }
      if (value.contains("\n") || value.contains("\r")) {
        throw new IllegalArgumentException(field + " 不得包含换行");
      }
      return value.strip();
    }

    /**
     * 校验 repo 为 owner/name 形式。
     *
     * @param value 仓库
     * @return 规范化仓库
     */
    private static String requireRepo(String value) {
      String normalized = requireText(value, "repo");
      if (!isValidRepo(normalized)) {
        throw new IllegalArgumentException("repo 必须为 owner/name 形式: " + value);
      }
      return normalized;
    }

    /**
     * 校验 64 位小写十六进制摘要。
     *
     * @param value 摘要值
     * @param field 字段名（错误信息用）
     * @return 小写摘要
     */
    private static String requireSha256(String value, String field) {
      String normalized = requireText(value, field).toLowerCase(Locale.ROOT);
      if (normalized.length() != SHA256_HEX_LENGTH || !normalized.matches("[0-9a-f]+")) {
        throw new IllegalArgumentException(field + " 必须为 64 位十六进制");
      }
      return normalized;
    }
  }

  /**
   * 计算文件字节的 SHA-256（小写十六进制）。
   *
   * @param bytes 报告文件字节
   * @return 64 位十六进制摘要
   */
  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }

  /**
   * 签发报告签名。
   *
   * @param secret HMAC 密钥（服务端持有；UTF-8 原始字节）
   * @param keyId 密钥标识
   * @param reportSha256 报告文件 SHA-256
   * @param repo 报告归属仓库（owner/name）
   * @param commit 报告绑定的代码提交（可空）
   * @param tier 档位（可空）
   * @param issuedAt 签发时间
   * @param runId 签发标识
   * @return 含签名值的信封
   */
  public static Envelope sign(
      byte[] secret,
      String keyId,
      String reportSha256,
      String repo,
      String commit,
      String tier,
      String issuedAt,
      String runId) {
    requireSecret(secret);
    Envelope unsigned =
        new Envelope(keyId, reportSha256, repo, commit, tier, issuedAt, runId, "0".repeat(64));
    String signature = hmacHex(secret, unsigned.canonicalText());
    return new Envelope(keyId, reportSha256, repo, commit, tier, issuedAt, runId, signature);
  }

  /**
   * 校验签名信封与报告摘要一致。
   *
   * @param secret HMAC 密钥
   * @param envelope 待验信封
   * @param actualReportSha256 实际报告文件 SHA-256
   * @return true = 摘要与签名都一致
   */
  public static boolean verify(byte[] secret, Envelope envelope, String actualReportSha256) {
    requireSecret(secret);
    if (envelope == null || actualReportSha256 == null) {
      return false;
    }
    String actual = actualReportSha256.strip().toLowerCase(Locale.ROOT);
    if (!actual.matches("[0-9a-f]{64}") || !actual.equals(envelope.reportSha256())) {
      return false;
    }
    byte[] expected = HexFormat.of().parseHex(hmacHex(secret, envelope.canonicalText()));
    byte[] provided = HexFormat.of().parseHex(envelope.signature());
    return MessageDigest.isEqual(expected, provided);
  }

  /**
   * 解析信封文本（严格行序与字段；未知行/缺失行即拒绝）。
   *
   * @param text 信封文本
   * @return 解析结果
   * @throws IllegalArgumentException 格式非法
   */
  public static Envelope parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("签名信封为空");
    }
    List<String> lines =
        Arrays.stream(text.split("\n", -1))
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .toList();
    if (lines.size() != 9) {
      throw new IllegalArgumentException("签名信封行数非法: " + lines.size() + "（期望 9）");
    }
    if (!SCHEME.equals(lines.get(0))) {
      throw new IllegalArgumentException("签名方案不支持: " + lines.get(0));
    }
    return new Envelope(
        value(lines.get(1), "key_id"),
        value(lines.get(2), "report_sha256"),
        value(lines.get(3), "repo"),
        value(lines.get(4), "commit"),
        value(lines.get(5), "tier"),
        value(lines.get(6), "issued_at"),
        value(lines.get(7), "run_id"),
        value(lines.get(8), "signature"));
  }

  private static String value(String line, String field) {
    String prefix = field + ":";
    if (!line.startsWith(prefix)) {
      throw new IllegalArgumentException("签名信封字段非法（期望 " + prefix + "）: " + line);
    }
    return line.substring(prefix.length()).strip();
  }

  private static void requireSecret(byte[] secret) {
    if (secret == null || secret.length == 0) {
      throw new IllegalArgumentException("HMAC 密钥为空");
    }
  }

  private static String hmacHex(byte[] secret, String message) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 不可用", e);
    }
  }
}
