package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.cra.common.exception.CraConfigException;

/**
 * 报告哈希（T3.3 L2 门禁：本地跑 + CI 校验报告哈希，04§2.4）。
 *
 * <p><strong>荣誉制局限（如实声明）</strong>：哈希只能证明"CI 看到的报告文件与作者提交的哈希一致"， 不能证明报告由当前 HEAD 真实生成——作者可伪造报告与哈希。M4b
 * 起可改用服务端 HMAC 签名路径（{@code verify --signature}，ADR-0008），签名防伪造但仍不等价于 服务端执行评测。报告内 {@code commit}
 * 字段提供弱绑定，CI 以 {@code --expect-commit} 校验；不防伪造，同属荣誉制。
 */
public final class ReportHasher {

  private ReportHasher() {}

  /**
   * 计算文件 SHA-256（小写十六进制）。
   *
   * @param file 文件
   * @return 十六进制摘要
   */
  public static String sha256(Path file) {
    MessageDigest digest = digest();
    try (DigestInputStream dis = new DigestInputStream(Files.newInputStream(file), digest)) {
      dis.transferTo(java.io.OutputStream.nullOutputStream());
    } catch (IOException e) {
      throw new CraConfigException("报告读取失败: " + file + "（" + e.getMessage() + "）", e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * 写哈希文件（纯十六进制 + 换行；CI 以 {@code --sha256-file} 校验）。
   *
   * @param file 报告文件
   * @param out 哈希文件路径
   * @return 摘要
   */
  public static String writeSha256File(Path file, Path out) {
    String hex = sha256(file);
    try {
      Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(out, hex + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CraConfigException("哈希文件写入失败: " + out + "（" + e.getMessage() + "）", e);
    }
    return hex;
  }

  /**
   * 读取哈希文件（容忍 sha256sum 的 "<hex> <file>" 两列格式）。
   *
   * @param file 哈希文件
   * @return 十六进制摘要（小写）
   */
  public static String readSha256File(Path file) {
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new CraConfigException("哈希文件读取失败: " + file + "（" + e.getMessage() + "）", e);
    }
    if (text.isEmpty()) {
      throw new CraConfigException("哈希文件为空: " + file);
    }
    String first = text.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
    if (!first.matches("[0-9a-f]{64}")) {
      throw new CraConfigException("哈希文件格式非法（需 sha256 十六进制）: " + file);
    }
    return first;
  }

  /**
   * 校验文件摘要。
   *
   * @param file 文件
   * @param expectedHex 期望摘要（大小写不敏感）
   * @return true = 一致
   */
  public static boolean matches(Path file, String expectedHex) {
    if (expectedHex == null || expectedHex.isBlank()) {
      return false;
    }
    String normalized = expectedHex.strip().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw new CraConfigException("--sha256 格式非法（需 64 位十六进制）: " + expectedHex);
    }
    return sha256(file).equals(normalized);
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new CraConfigException("JVM 不支持 SHA-256");
    }
  }
}
