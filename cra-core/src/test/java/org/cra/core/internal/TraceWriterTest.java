package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.AgentRole;
import org.cra.agents.api.ToolWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** trace 写入纪律：首次失败即抛（基线语义），中断写清中断重试一次，不得永久关闭写入面。 */
class TraceWriterTest {

  @TempDir Path tmp;

  @Test
  void firstWriteFailureThrowsUncheckedIoException() {
    TraceWriter trace =
        new TraceWriter(
            tmp,
            "run_fail",
            (path, content, append) -> {
              throw new IOException("disk full");
            });
    Map<String, Object> payload = Map.of("x", 1);
    assertThatThrownBy(() -> trace.emit("run_start", payload))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("trace 写入失败");
    AgentBrief brief = brief();
    assertThatThrownBy(() -> trace.persistBrief(brief, "chunk"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("brief 落盘失败");
  }

  @Test
  void writeFailureDoesNotPermanentlyDisableWriter() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TraceWriter trace =
        new TraceWriter(
            tmp,
            "run_retry",
            (path, content, append) -> {
              if (calls.getAndIncrement() == 0) {
                throw new IOException("transient");
              }
              write(path, content, append);
            });
    assertThatThrownBy(() -> trace.emit("first", Map.of()))
        .isInstanceOf(UncheckedIOException.class);
    trace.emit("second", Map.of("ok", true));
    String body = Files.readString(trace.runDir().resolve("trace.ndjson"));
    assertThat(body).contains("\"event\":\"second\"").doesNotContain("\"event\":\"first\"");
  }

  @Test
  void interruptClosedWriteRetriesOnceAndClearsInterrupt() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TraceWriter trace =
        new TraceWriter(
            tmp,
            "run_interrupt",
            (path, content, append) -> {
              if (calls.getAndIncrement() == 0) {
                throw new ClosedByInterruptException();
              }
              write(path, content, append);
            });
    Thread.currentThread().interrupt();
    trace.emit("after_cancel", Map.of("ok", true));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    assertThat(Files.readString(trace.runDir().resolve("trace.ndjson")))
        .contains("\"event\":\"after_cancel\"");
  }

  @Test
  void interruptClosedWriteThrowsWhenRetryAlsoFails() {
    TraceWriter trace =
        new TraceWriter(
            tmp,
            "run_interrupt_fail",
            (path, content, append) -> {
              throw new ClosedByInterruptException();
            });
    Thread.currentThread().interrupt();
    assertThatThrownBy(() -> trace.emit("x", Map.of()))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("中断重试仍失败");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  void constructorThrowsWhenRunRootIsUnusable() throws Exception {
    Path file = Files.writeString(tmp.resolve("blocker"), "x");
    assertThatThrownBy(() -> new TraceWriter(file, "run_bad"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("trace 目录创建失败");
  }

  private static void write(Path path, String content, boolean append) throws IOException {
    if (append) {
      Files.writeString(
          path,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } else {
      Files.writeString(path, content, StandardCharsets.UTF_8);
    }
  }

  private static AgentBrief brief() {
    return new AgentBrief(
        new AgentRole("bug-hunter/dataflow", "bh"),
        "# System\ns",
        "u",
        "t@00000000",
        ToolWhitelist.NONE,
        new AgentBudget(1_000, 100, 1, 1_000));
  }
}
