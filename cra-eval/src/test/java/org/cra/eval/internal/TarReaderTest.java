package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.cra.common.exception.CraIntegrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** tar 解包边界（06§3）：PAX path 覆盖、路径逃逸、截断与校验和。 */
class TarReaderTest {

  private static final int BLOCK = 512;

  @TempDir Path tmp;

  @Test
  void paxPathOverrideWinsOverTruncatedUstarName() throws Exception {
    byte[] payload = paxRecord("path", "deep/中文.java");
    byte[] content = "class A {}\n".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    writeEntry(tar, header("PaxHeader", payload.length, 'x'), payload);
    writeEntry(tar, header("trunc.java", content.length, '0'), content);
    tar.writeBytes(new byte[BLOCK * 2]);

    Path out = Files.createDirectories(tmp.resolve("out"));
    TarReader.Result result =
        TarReader.untar(new ByteArrayInputStream(tar.toByteArray()), out);
    assertThat(result.files()).isEqualTo(1);
    assertThat(out.resolve("deep/中文.java")).hasContent("class A {}\n");
  }

  @Test
  void rejectsParentTraversalForFilesAndDirectories() throws Exception {
    Path out = Files.createDirectories(tmp.resolve("out"));
    assertThatThrownBy(
            () ->
                TarReader.untar(
                    new ByteArrayInputStream(tar(header("../../evil.txt", 1, '0'), "x".getBytes())),
                    out))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("非法");
    assertThatThrownBy(
            () ->
                TarReader.untar(
                    new ByteArrayInputStream(tar(header("../../evil-dir", 0, '5'), new byte[0])),
                    out))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("非法");
  }

  @Test
  void rejectsTruncatedPayload() throws Exception {
    Path out = Files.createDirectories(tmp.resolve("out"));
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    tar.write(header("a.txt", 100, '0'));
    tar.write("short".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () -> TarReader.untar(new ByteArrayInputStream(tar.toByteArray()), out))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("截断");
  }

  @Test
  void rejectsCorruptHeaderChecksum() throws Exception {
    Path out = Files.createDirectories(tmp.resolve("out"));
    byte[] header = header("a.txt", 1, '0');
    header[0] = 'b'; // 改名但不重算校验和
    assertThatThrownBy(
            () ->
                TarReader.untar(
                    new ByteArrayInputStream(tar(header, "x".getBytes())), out))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("校验和");
  }

  private static byte[] tar(byte[] header, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeEntry(out, header, payload);
    out.writeBytes(new byte[BLOCK * 2]);
    return out.toByteArray();
  }

  private static void writeEntry(ByteArrayOutputStream out, byte[] header, byte[] payload) {
    out.writeBytes(header);
    out.writeBytes(payload);
    int pad = (BLOCK - payload.length % BLOCK) % BLOCK;
    out.writeBytes(new byte[pad]);
  }

  private static byte[] header(String name, long size, char type) {
    byte[] h = new byte[BLOCK];
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    assertThat(nameBytes.length).isLessThanOrEqualTo(100);
    System.arraycopy(nameBytes, 0, h, 0, nameBytes.length);
    put(h, 100, "0000644");
    put(h, 108, "0000000");
    put(h, 116, "0000000");
    put(h, 124, String.format("%011o", size));
    put(h, 136, "00000000000");
    for (int i = 148; i < 156; i++) {
      h[i] = ' ';
    }
    put(h, 156, String.valueOf(type));
    put(h, 257, "ustar");
    h[262] = 0;
    put(h, 263, "00");
    int checksum = 0;
    for (byte b : h) {
      checksum += b & 0xff;
    }
    put(h, 148, String.format("%06o", checksum));
    h[154] = 0;
    h[155] = ' ';
    return h;
  }

  private static byte[] paxRecord(String key, String value) {
    String body = " " + key + "=" + value + "\n";
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    int length = bodyBytes.length + 2;
    while (String.valueOf(length).length() + bodyBytes.length != length) {
      length = String.valueOf(length).length() + bodyBytes.length;
    }
    byte[] record = (length + body).getBytes(StandardCharsets.UTF_8);
    assertThat(record.length).isEqualTo(length);
    return record;
  }

  private static void put(byte[] target, int offset, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(bytes, 0, target, offset, bytes.length);
  }
}
