package org.cra.eval.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import org.cra.common.exception.CraIntegrationException;

/**
 * 最小 USTAR 写入器（T3.5 badcase 快照生成；与 {@link TarReader} 对称，不引第三方依赖，09§7）。
 *
 * <p>只写常规文件（不写目录/符号链接）；路径超长时按 USTAR prefix 拆分，拆不下则显式失败——不静默截断路径。
 */
final class TarWriter {

  private static final int BLOCK = 512;

  private TarWriter() {}

  /**
   * 写 .tar.gz。
   *
   * @param target 目标文件（如 repo_snapshot.tgz）
   * @param files 相对路径 → 内容（按路径排序写入，产物可复现）
   * @return 写入文件数
   */
  static int writeTgz(Path target, Map<String, byte[]> files) {
    Map<String, byte[]> sorted = new TreeMap<>(files);
    long mtime = 0;
    try {
      Files.createDirectories(target.toAbsolutePath().getParent());
      try (OutputStream file = Files.newOutputStream(target);
          GZIPOutputStream gz = new GZIPOutputStream(file)) {
        for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
          byte[] content = entry.getValue();
          gz.write(header(entry.getKey(), content.length, mtime));
          gz.write(content);
          int padding = (BLOCK - content.length % BLOCK) % BLOCK;
          if (padding > 0) {
            gz.write(new byte[padding]);
          }
        }
        gz.write(new byte[BLOCK * 2]);
      }
      return sorted.size();
    } catch (IOException e) {
      throw new CraIntegrationException("快照写入失败: " + target, e);
    }
  }

  /**
   * 构造 512 字节 USTAR 头。
   *
   * @param path 相对路径
   * @param size 内容长度
   * @param mtime 修改时间（epoch 秒；0 = 可复现）
   * @return 头块
   */
  static byte[] header(String path, long size, long mtime) {
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    if (normalized.isEmpty() || normalized.contains("..")) {
      throw new CraIntegrationException("快照路径非法: " + path);
    }
    String name = normalized;
    String prefix = "";
    if (normalized.getBytes(StandardCharsets.UTF_8).length > 100) {
      int split = normalized.lastIndexOf('/');
      if (split <= 0) {
        throw new CraIntegrationException("路径超过 USTAR name 上限且无法拆分: " + path);
      }
      prefix = normalized.substring(0, split);
      name = normalized.substring(split + 1);
      if (prefix.getBytes(StandardCharsets.UTF_8).length > 155
          || name.getBytes(StandardCharsets.UTF_8).length > 100) {
        throw new CraIntegrationException("路径超过 USTAR name/prefix 上限: " + path);
      }
    }
    byte[] header = new byte[BLOCK];
    put(header, 0, 100, name);
    put(header, 100, 8, "0000644");
    put(header, 108, 8, "0000000");
    put(header, 116, 8, "0000000");
    put(header, 124, 12, String.format("%011o", size));
    put(header, 136, 12, String.format("%011o", mtime));
    for (int i = 148; i < 156; i++) {
      header[i] = ' ';
    }
    put(header, 156, 1, "0");
    put(header, 257, 6, "ustar");
    header[262] = 0;
    put(header, 263, 2, "00");
    int checksum = 0;
    for (byte b : header) {
      checksum += b & 0xff;
    }
    String sum = String.format("%06o", checksum);
    put(header, 148, 8, sum + "\0 ");
    if (!prefix.isEmpty()) {
      put(header, 345, 155, prefix);
    }
    return header;
  }

  private static void put(byte[] target, int offset, int length, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > length) {
      throw new CraIntegrationException(
          "tar 头字段超长: " + value + "（" + bytes.length + " > " + length + "）");
    }
    System.arraycopy(bytes, 0, target, offset, bytes.length);
  }
}
