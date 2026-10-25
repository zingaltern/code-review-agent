package org.cra.common.git;

import java.util.Objects;

/**
 * 单文件内容（GitPlatformAdapter.readFile 返回值，05§2.1；锚行内容哈希与 L1 全文注入用）。
 *
 * @param path 文件路径
 * @param text UTF-8 文本内容（二进制文件为 null）
 * @param binary 是否二进制（true 时 text 恒为 null）
 */
public record FileContent(String path, String text, boolean binary) {

  public FileContent {
    Objects.requireNonNull(path, "path");
    if (path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    if (binary && text != null) {
      throw new IllegalArgumentException("binary file must not carry text");
    }
  }

  /**
   * 便捷构造：文本文件。
   *
   * @param path 文件路径
   * @param text 文本内容
   * @return FileContent
   */
  public static FileContent ofText(String path, String text) {
    return new FileContent(path, text, false);
  }
}
