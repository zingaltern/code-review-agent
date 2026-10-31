package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval verify} 参数解析（T3.3：CI 校验本地 L2 报告；M4b：服务端签名）。 */
final class VerifyArgsParser {

  /** 默认签名密钥环境变量（与 server 的 CRA_SERVER_SIGNING_KEY 同值，CI 侧独立配置，05§6）。 */
  static final String DEFAULT_SIGNING_KEY_ENV = "CRA_REPORT_SIGNING_KEY";

  /** verify 接受的全部旗标（与 EvalUsage 同步）。 */
  private static final List<String> FLAGS =
      List.of(
          "--report",
          "--sha256",
          "--sha256-file",
          "--tier",
          "--expect-commit",
          "--repo",
          "--signature",
          "--signing-key-env",
          "--signing-key-file",
          "--signing-key-id");

  private VerifyArgsParser() {}

  static EvalCliArgs.VerifyArgs parse(EvalArgOptions options) {
    options.rejectUnknownFlags(FLAGS);
    options.rejectPositional();
    Map<String, String> named = options.named();
    String report = named.get("--report");
    if (report == null || report.isBlank()) {
      throw new CraConfigException("缺参数: --report <json>（verify 校验本地 L2 报告，04§2.4）");
    }
    String sha256 = named.get("--sha256");
    String sha256File = named.get("--sha256-file");
    String signature = named.get("--signature");
    boolean hasSignature = signature != null && !signature.isBlank();
    requireHashOrSignature(sha256, sha256File, hasSignature);
    String tier = named.get("--tier");
    if (tier != null && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("verify --tier 非法: " + tier + "（l1|l2|l3）");
    }
    String repo = named.get("--repo");
    validateRepo(repo, hasSignature);
    String signingKeyFile = named.get("--signing-key-file");
    if (!hasSignature && signingKeyFile != null) {
      throw new CraConfigException("--signing-key-file 仅与 --signature 同时使用");
    }
    return new EvalCliArgs.VerifyArgs(
        Path.of(report),
        sha256,
        sha256File == null ? null : Path.of(sha256File),
        tier,
        named.get("--expect-commit"),
        repo,
        hasSignature ? Path.of(signature) : null,
        named.getOrDefault("--signing-key-env", DEFAULT_SIGNING_KEY_ENV),
        signingKeyFile == null ? null : Path.of(signingKeyFile),
        named.get("--signing-key-id"));
  }

  /**
   * 至少提供一种校验凭据（哈希或签名）。
   *
   * @param sha256 哈希值
   * @param sha256File 哈希文件
   * @param hasSignature 是否有签名
   */
  private static void requireHashOrSignature(
      String sha256, String sha256File, boolean hasSignature) {
    if ((sha256 == null || sha256.isBlank())
        && (sha256File == null || sha256File.isBlank())
        && !hasSignature) {
      throw new CraConfigException(
          "缺参数: --sha256 <hex>、--sha256-file <path> 或 --signature <path>（至少一个）");
    }
  }

  /**
   * --repo 仅与 --signature 同时使用。
   *
   * @param repo --repo 值
   * @param hasSignature 是否有签名
   */
  private static void validateRepo(String repo, boolean hasSignature) {
    if (repo != null && !repo.isBlank() && !hasSignature) {
      throw new CraConfigException("--repo 仅与 --signature 同时使用（签名验签的仓库限定绑定）");
    }
  }
}
