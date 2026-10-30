package org.cra.common.text;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 跨模块共享的纯文本辅助（无状态、无依赖）：短 sha、错误信息截断、必填校验、首个非空值与严格 UTF-8 解码。
 *
 * <p>集中此前散落在各模块的复制实现，避免同名方法语义漂移（尤其 7/8 位 sha 与 200 字符截断）； 严格 UTF-8 于 2026-10-31 收敛
 * （ADR-0016：github/gitlab/jgit 三份逐字节相同实现的第四处收敛点）。
 */
public final class Texts {

  private Texts() {}

  /**
   * 短 sha（按长度截断，空值返回调用方指定占位）。
   *
   * @param sha 原始 sha（可空）
   * @param length 保留长度（&gt;0）
   * @param fallback 空值/空串时的返回值
   * @return 短 sha 或 fallback
   */
  public static String shortSha(String sha, int length, String fallback) {
    if (sha == null || sha.isBlank()) {
      return fallback;
    }
    return sha.substring(0, Math.min(length, sha.length()));
  }

  /**
   * 单行截断（错误信息/摘要用）：超长追加省略号，null 视为空串。
   *
   * @param text 原始文本
   * @param max 最大字符数
   * @return 截断后的文本
   */
  public static String truncate(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }

  /**
   * 必填非空白校验（数据模型构造器用）。
   *
   * @param value 待校验值
   * @param field 字段名（错误信息）
   * @throws IllegalArgumentException 值为 null 或空白
   */
  public static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }

  /**
   * 返回首个非空白值。
   *
   * @param values 候选值
   * @return 首个非空白值；全部为空时返回 null
   */
  public static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * 严格 UTF-8 解码（平台文件内容读取用，05§2.1 {@code readFile}）：malformed/unmappable 一律 {@link
   * CodingErrorAction#REPORT}，不做任何静默替换。
   *
   * <p>降级语义（显式声明）：解码失败抛 {@link CharacterCodingException}，由调用方降级为 binary 语义 （{@code
   * FileContent.text=null, binary=true}，02§2），绝不产出带 U+FFFD 的伪文本——替换后的文本会污染锚行定位与指纹输入。
   *
   * @param bytes 原始字节
   * @return 解码后的文本
   * @throws CharacterCodingException 字节序列不是合法 UTF-8
   */
  public static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
