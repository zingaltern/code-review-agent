package org.cra.eval.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.cra.common.exception.CraIntegrationException;

/**
 * 最小 USTAR/PAX/GNU tar 读取器（仅解包 fixture 的 repo_snapshot.tgz，06§3 规格）。 不引第三方依赖（09§7）：只处理常规文件/目录； pax
 * 'x' 头按长度前缀解析 path 覆盖、GNU 'L' 长名支持；macOS AppleDouble（._ 条目）与符号链接等跳过并计数。 tar 条目负载一律按 512 块对齐消费。
 *
 * <p>边界纪律：归档是外部输入——头校验和必须匹配、截断必须报错、大小有上限、解包路径必须落在目标目录内（目录条目同样适用）。
 */
public final class TarReader {

  private static final int BLOCK = 512;

  /** 单条目负载上限（含 pax/L 扩展头）。 */
  static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

  /** 单次解包总量上限（防解压炸弹）。 */
  static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

  private TarReader() {}

  /** 解包结果。 */
  public record Result(int files, int dirs, int skipped) {}

  /**
   * 解包 .tar（不含压缩）到目标目录。
   *
   * @param tar tgz 已解压出的 tar 数据流
   * @param targetDir 目标目录（须已存在）
   * @return 解包统计
   * @throws CraIntegrationException 归档损坏/截断/路径逃逸/超出大小上限
   */
  public static Result untar(InputStream tar, Path targetDir) throws IOException {
    int files = 0;
    int dirs = 0;
    int skipped = 0;
    long totalBytes = 0;
    Path root = targetDir.toAbsolutePath().normalize();
    byte[] header = new byte[BLOCK];
    String pendingPathOverride = null;
    while (true) {
      int read = readFully(tar, header);
      if (read == 0) {
        break;
      }
      if (read < BLOCK) {
        throw new CraIntegrationException("tar 数据截断：头部不足 " + BLOCK + " 字节");
      }
      if (isZeroBlock(header)) {
        break;
      }
      verifyChecksum(header);
      String name = readString(header, 0, 100);
      long size = readOctal(header, 124, 12);
      char type = (char) (header[156] & 0xFF);
      String prefix = readString(header, 345, 155);
      String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
      if (size < 0 || size > MAX_ENTRY_BYTES) {
        throw new CraIntegrationException("tar 条目大小非法: " + size + "（" + fullName + "）");
      }
      long padded = paddedSize(size);
      if (type == 'x' || type == 'g' || type == 'L') {
        byte[] payload = new byte[(int) size];
        if (readFully(tar, payload) != payload.length) {
          throw new CraIntegrationException("tar 扩展头负载截断: " + fullName);
        }
        skipFully(tar, padded - size);
        if (type == 'x') {
          String override = paxPath(payload);
          if (override != null) {
            pendingPathOverride = override;
          }
        } else if (type == 'L') {
          pendingPathOverride =
              new String(payload, StandardCharsets.UTF_8).replace("\0", "").strip();
        }
        continue;
      }
      String path = pendingPathOverride != null ? pendingPathOverride : fullName;
      pendingPathOverride = null;
      if (type == '5') {
        if (isAppleDouble(path)) {
          skipped++;
          skipFully(tar, padded);
          continue;
        }
        Files.createDirectories(resolveSafe(root, path));
        dirs++;
        continue;
      }
      if (type == '0' || type == '\0' || type == '7') {
        if (isAppleDouble(path)) {
          skipped++;
          skipFully(tar, padded);
          continue;
        }
        Path target = resolveSafe(root, path);
        totalBytes += size;
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new CraIntegrationException("tar 解包总量超过上限 " + MAX_TOTAL_BYTES + " 字节");
        }
        Files.createDirectories(target.getParent());
        long remaining = size;
        byte[] buf = new byte[8192];
        try (OutputStream out =
            Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
          while (remaining > 0) {
            int n = tar.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
              throw new CraIntegrationException("tar 数据截断: " + path);
            }
            out.write(buf, 0, n);
            remaining -= n;
          }
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

  /** 路径归一 + 越界拒绝：绝对路径、盘符、任何 `..` 段一律拒绝（目录与文件同规则）。 */
  private static Path resolveSafe(Path root, String rawPath) {
    String path = rawPath.replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    if (path.isEmpty()
        || path.startsWith("/")
        || path.matches("(?i)^[a-z]:/.*")
        || containsParentSegment(path)
        || isAppleDouble(path)) {
      throw new CraIntegrationException("tar 条目路径非法（拒绝解包）: " + rawPath);
    }
    Path resolved = root.resolve(path).normalize();
    if (!resolved.startsWith(root)) {
      throw new CraIntegrationException("tar 条目路径逃逸目标目录: " + rawPath);
    }
    return resolved;
  }

  private static boolean containsParentSegment(String path) {
    for (String segment : path.split("/")) {
      if ("..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private static long paddedSize(long size) {
    long remainder = size % BLOCK;
    return remainder == 0 ? size : size + (BLOCK - remainder);
  }

  /**
   * POSIX PAX 记录：{@code "<length> <key>=<value>\n"}，length 为整条记录字节数；value 可含换行。 旧实现按 {@code
   * startsWith("path=")} 匹配，永远不命中——长路径/非 ASCII 路径会被按 ustar 截断名落盘。
   */
  private static String paxPath(byte[] payload) {
    int position = 0;
    while (position < payload.length) {
      int space = position;
      while (space < payload.length && payload[space] != ' ') {
        space++;
      }
      if (space >= payload.length) {
        break;
      }
      long recordLength;
      try {
        recordLength =
            Long.parseLong(
                new String(payload, position, space - position, StandardCharsets.US_ASCII));
      } catch (NumberFormatException e) {
        throw new CraIntegrationException("PAX 记录长度非法");
      }
      if (recordLength <= 0 || position + recordLength > payload.length) {
        throw new CraIntegrationException("PAX 记录越界: " + recordLength);
      }
      int recordEnd = position + (int) recordLength;
      int contentEnd = recordEnd;
      while (contentEnd > space + 1 && (payload[contentEnd - 1] == '\n')) {
        contentEnd--;
      }
      String record =
          new String(payload, space + 1, contentEnd - (space + 1), StandardCharsets.UTF_8);
      int eq = record.indexOf('=');
      if (eq > 0 && "path".equals(record.substring(0, eq))) {
        return record.substring(eq + 1);
      }
      position = recordEnd;
    }
    return null;
  }

  private static void verifyChecksum(byte[] header) {
    long stored = readOctalAllowZero(header, 148, 8);
    if (stored <= 0) {
      throw new CraIntegrationException("tar 头缺校验和");
    }
    long unsigned = 0;
    long signed = 0;
    for (int i = 0; i < header.length; i++) {
      int b = i >= 148 && i < 156 ? ' ' : (header[i] & 0xFF);
      unsigned += b;
      signed += (byte) (i >= 148 && i < 156 ? ' ' : header[i]);
    }
    if (stored != unsigned && stored != signed) {
      throw new CraIntegrationException("tar 头校验和不匹配: stored=" + stored + " actual=" + unsigned);
    }
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
    // GNU base-256 高位标记
    if ((block[offset] & 0x80) != 0) {
      long value = 0;
      for (int i = offset + 1; i < offset + length; i++) {
        if (value > (Long.MAX_VALUE >> 8)) {
          throw new CraIntegrationException("tar 头数值溢出（base-256）");
        }
        value = (value << 8) | (block[i] & 0xFFL);
      }
      return value;
    }
    String s = readString(block, offset, length);
    if (s.isEmpty()) {
      return 0;
    }
    try {
      return Long.parseLong(s.replace(" ", "").replace("\0", ""), 8);
    } catch (NumberFormatException e) {
      throw new CraIntegrationException("tar 头八进制数值非法: " + s);
    }
  }

  private static long readOctalAllowZero(byte[] block, int offset, int length) {
    try {
      return readOctal(block, offset, length);
    } catch (CraIntegrationException e) {
      return -1;
    }
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
