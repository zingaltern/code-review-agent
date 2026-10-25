package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** USTAR 写入与读取往返（badcase 快照；长路径走 prefix 拆分）。 */
class TarWriterTest {

  @TempDir Path tmp;

  @Test
  void roundTripsFilesIncludingLongPaths() throws Exception {
    String longPrefix = "a/".repeat(60) + "b"; // >100 字节，需 prefix 拆分
    Path tgz = tmp.resolve("snap.tgz");
    int written =
        TarWriter.writeTgz(
            tgz,
            Map.of(
                "src/main/java/Foo.java",
                "class Foo {}\n".getBytes(StandardCharsets.UTF_8),
                longPrefix + "/Leaf.java",
                "class Leaf {}\n".getBytes(StandardCharsets.UTF_8)));
    assertThat(written).isEqualTo(2);

    Path out = tmp.resolve("out");
    Files.createDirectories(out);
    try (InputStream in = new GZIPInputStream(Files.newInputStream(tgz))) {
      TarReader.Result result = TarReader.untar(in, out);
      assertThat(result.files()).isEqualTo(2);
    }
    assertThat(out.resolve("src/main/java/Foo.java")).hasContent("class Foo {}\n");
    assertThat(out.resolve(longPrefix + "/Leaf.java")).hasContent("class Leaf {}\n");
  }
}
