package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** JdkHttpTransport 纯逻辑（响应体读取上限）单测——HTTP 行为在集成层（08§2.2）。 */
class JdkHttpTransportTest {

  @Test
  void readBoundedReadsBodyWithinLimit() throws Exception {
    assertThat(
            JdkHttpTransport.readBounded(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))))
        .isEqualTo("hello");
  }

  @Test
  void readBoundedRejectsBodyOverLimit() {
    // 超过 8MB 上限：协议级异常（防外部可控堆耗尽），不静默截断
    byte[] oversized = new byte[JdkHttpTransport.MAX_RESPONSE_BYTES + 1];
    Arrays.fill(oversized, (byte) 'a');
    assertThatIOException()
        .isThrownBy(() -> JdkHttpTransport.readBounded(new ByteArrayInputStream(oversized)))
        .withMessageContaining("上限");
  }
}
