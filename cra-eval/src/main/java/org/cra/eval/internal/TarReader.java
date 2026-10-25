package org.cra.eval.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraIntegrationException;

/**
 * 最小 USTAR/PAX/GNU tar 读取器（仅解包 fixture 的 repo_snapshot.tgz，06§3 规格）。 不引第三方依赖（09§7）：只处理常规文件/目录； pax
 * 'x' 头取 path 覆盖、GNU 'L' 长名支持；macOS AppleDouble（._ 条目）与符号链接等跳过并计数。 tar 条目负载一律按 512 块对齐消费。
 */
public final class TarReader {

  private static final int BLOCK = 512;

  private TarReader() {}

  /** 解包结果。 */
  public record Result(int files, int dirs, int skipped) {}

  /**
   * 解包 .tar（不含压缩）到目标目录。
   *
   * @param tar tgz 已解压出的 tar 数据流
   * @param targetDir 目标目录（须已存在）
   * @return 解包统计
   */
  public static Result untar(InputStream tar, Path targetDir) throws IOException {
    int files = 0;
    int dirs = 0;
    int skipped = 0;
    byte[] header = new byte[BLOCK];
    String pendingPathOverride = null;
    while (true) {
      int read = readFully(tar, header);
      if (read < BLOCK) {
        break;
      }
      if (isZeroBlock(header)) {
        break;
      }
      String name = readString(header, 0, 100);
      long size = readOctal(header, 124, 12);
      char type = (char) (header[156] & 0xFF);
      String prefix = readString(header, 345, 155);
      String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
      long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
      if (type == 'x' || type == 'g' || type == 'L') {
        byte[] payload = new byte[(int) size];
        readFully(tar, payload);
        skipFully(tar, padded - size);
        if (type == 'x') {
          String override = paxPath(payload);
          if (override != null) {
            pendingPathOverride = override;
          }
        } else if (type == 'L') {
          pendingPathOverride =
              new String(payload, StandardCharsets.UTF_8).trim().replace("\0", "");
        }
        continue;
      }
      String path = pendingPathOverride != null ? pendingPathOverride : fullName;
      pendingPathOverride = null;
      path = normalize(path);
      if (type == '5') {
        Files.createDirectories(targetDir.resolve(path));
        dirs++;
        continue;
      }
      if (type == '0' || type == '\0' || type == '7') {
        if (path.isEmpty() || path.contains("..") || isAppleDouble(path)) {
          skipped++;
          skipFully(tar, padded);
          continue;
        }
        Path target = targetDir.resolve(path);
        Files.createDirectories(target.getParent());
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          long remaining = size;
          byte[] buf = new byte[8192];
          while (remaining > 0) {
            int n = tar.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
              throw new CraIntegrationException("tar 数据截断: " + path);
            }
            out.write(buf, 0, n);
            remaining -= n;
          }
          Files.write(target, out.toByteArray());
        }
        files++;
        skipFully(tar, padded - size);
        continue;
      }
      skipped++;
      skipFully(tar, padded);
    }
    return new Result(files, dirs, skipped);
  }

  /** macOS AppleDouble 元数据条目（bsdtar 在有 xattr 的文件上自动附带，与快照内容无关）。 */
  private static boolean isAppleDouble(String path) {
    String base = path.substring(path.lastIndexOf('/') + 1);
    return base.startsWith("._") || base.equals(".DS_Store") || path.contains("/__MACOSX/");
  }

  private static String normalize(String path) {
    String p = path.replace("\\", "/");
    while (p.startsWith("./")) {
      p = p.substring(2);
    }
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    return p;
  }

  private static String paxPath(byte[] payload) {
    String text = new String(payload, StandardCharsets.UTF_8);
    for (String record : text.split("\n")) {
      if (record.startsWith("path=")) {
        return record.substring("path=".length()).replace("\0", "").trim();
      }
    }
    return null;
  }

  private static boolean isZeroBlock(byte[] block) {
    for (byte b : block) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  private static String readString(byte[] block, int offset, int length) {
    int end = offset;
    int limit = offset + length;
    while (end < limit && block[end] != 0) {
      end++;
    }
    return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
  }

  private static long readOctal(byte[] block, int offset, int length) {
    String s = readString(block, offset, length);
    if (s.isEmpty()) {
      return 0;
    }
    // GNU base-256 高位标记
    if ((block[offset] & 0x80) != 0) {
      long value = 0;
      for (int i = offset + 1; i < offset + length; i++) {
        value = (value << 8) | (block[i] & 0xFFL);
      }
      return value;
    }
    return Long.parseLong(s.replace(" ", ""), 8);
  }

  private static int readFully(InputStream in, byte[] buf) throws IOException {
    int total = 0;
    while (total < buf.length) {
      int n = in.read(buf, total, buf.length - total);
      if (n < 0) {
        break;
      }
      total += n;
    }
    return total;
  }

  private static void skipFully(InputStream in, long size) throws IOException {
    long remaining = size;
    byte[] sink = new byte[8192];
    while (remaining > 0) {
      long skippedBytes = in.skip(remaining);
      if (skippedBytes > 0) {
        remaining -= skippedBytes;
        continue;
      }
      int n = in.read(sink, 0, (int) Math.min(sink.length, remaining));
      if (n < 0) {
        throw new CraIntegrationException("tar 数据截断（跳过块时）");
      }
      remaining -= n;
    }
  }
}
