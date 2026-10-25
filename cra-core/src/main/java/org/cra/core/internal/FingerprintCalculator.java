package org.cra.core.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 指纹 v2 计算（02§4.1）：只含确定性成分，不含任何 LLM 生成文本与绝对行号——
 *
 * <pre>
 * fingerprint = sha256( file_path | category | anchor_line_content_hash | anchor_context_hash )
 * anchor_line_content_hash = sha256(锚行内容去首尾空白)
 * anchor_context_hash      = sha256(锚行 ±2 行内容联合哈希，逐行去首尾空白后 \n 连接)
 * </pre>
 *
 * <p>标记内 fp 字段 = fingerprint 十六进制截断 16 位（05§1.3）。滑窗容错匹配随 M1 落地。
 */
final class FingerprintCalculator {

  private FingerprintCalculator() {}

  /**
   * 计算 finding 指纹。
   *
   * @param file 文件路径
   * @param category 类别（小写，账本标记形态）
   * @param anchorLineContent 锚行内容（该行原文）
   * @param contextLines 锚行 ±2 行内容（有序，含锚行；可不足 5 行）
   * @return 十六进制截断指纹（16 位）
   */
  static String fingerprint(
      String file, String category, String anchorLineContent, List<String> contextLines) {
    String lineHash = sha256(anchorLineContent.strip());
    String contextHash =
        sha256(contextLines.stream().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse(""));
    return sha256(file + "|" + category + "|" + lineHash + "|" + contextHash).substring(0, 16);
  }

  /**
   * 单测锚点：指纹随行内容漂移而稳定、随内容变更而改变（02§4.1 语义的回归锚）。
   *
   * @param value 任意文本
   * @return sha256 十六进制
   */
  static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
