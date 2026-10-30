package org.cra.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import org.cra.agents.api.AgentBrief;

/**
 * trace 写入器（05§1.4 NDJSON）：.cra/runs/&lt;run_id&gt;/trace.ndjson + briefs/*.md（brief 全文随 trace 存于同
 * artifact——M0 验收 4）。事件集对齐 05§1.4，M0 新增编排步骤事件先行（07§6.5）。
 *
 * <p>基线语义：首次写失败即抛 {@link UncheckedIOException}——trace 是验收证据，静默降级会让"无 trace"与"无事件" 不可区分。唯一例外：共享池
 * future.cancel(true) 造成的中断写（{@link ClosedByInterruptException}）清中断后重试一次； 中断不得把写入器永久关闭，重试仍失败才抛。
 */
public final class TraceWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 可注入写入面（测试模拟磁盘故障/中断写；生产走 Files.writeString）。 */
  @FunctionalInterface
  interface FileSink {
    void write(Path path, String content, boolean append) throws IOException;
  }

  private final Path runDir;
  private final String runId;
  private final Object lock = new Object();
  private final FileSink sink;

  public TraceWriter(Path runRoot, String runId) {
    this(runRoot, runId, TraceWriter::writeFile);
  }

  /** 测试形态：注入写入面（目录创建仍走真实文件系统）。 */
  TraceWriter(Path runRoot, String runId, FileSink sink) {
    this.runId = runId;
    this.runDir = runRoot.resolve(runId);
    this.sink = sink;
    try {
      Files.createDirectories(runDir.resolve("briefs"));
    } catch (IOException e) {
      throw new UncheckedIOException("trace 目录创建失败: " + runDir, e);
    }
  }

  public Path runDir() {
    return runDir;
  }

  /**
   * 追加一条事件（ts/run_id/event 前置，字段顺序即文档形态）。
   *
   * @param event 事件名（05§1.4，snake_case）
   * @param fields 事件字段
   * @throws UncheckedIOException 写入失败（中断写先清中断重试一次）
   */
  public void emit(String event, Map<String, Object> fields) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("ts", Instant.now().toString());
    node.put("run_id", runId);
    node.put("event", event);
    fields.forEach((k, v) -> node.set(k, MAPPER.valueToTree(v)));
    synchronized (lock) {
      write(runDir.resolve("trace.ndjson"), node + "\n", true, "trace 写入失败");
    }
  }

  /**
   * brief 全文落盘（system + user 分段）。
   *
   * @param brief 自包含简报
   * @return brief 文件路径（进 agent_start 事件的 brief_path）
   * @throws UncheckedIOException 写入失败（中断写先清中断重试一次）
   */
  public Path persistBrief(AgentBrief brief) {
    return persistBrief(brief, null);
  }

  /**
   * brief 全文落盘（M1 分 chunk 形态：同角色多 chunk 以后缀区分文件）。
   *
   * @param brief 自包含简报
   * @param suffix 文件名后缀（如 chunk 序号），null 时用角色 id
   * @return brief 文件路径
   * @throws UncheckedIOException 写入失败（中断写先清中断重试一次）
   */
  public Path persistBrief(AgentBrief brief, String suffix) {
    Path path = briefPath(brief, suffix);
    String content = "# system\n" + brief.systemPrompt() + "\n\n# user\n" + brief.userPrompt();
    write(path, content, false, "brief 落盘失败: " + path);
    return path;
  }

  /** 首次失败即抛；仅 ClosedByInterruptException 清中断重试一次（共享池 cancel 的副作用）。 */
  private void write(Path path, String content, boolean append, String message) {
    try {
      sink.write(path, content, append);
    } catch (ClosedByInterruptException e) {
      // future.cancel(true) 打断写通道：清中断后重试一次，不得因此永久放弃写入面
      Thread.interrupted();
      try {
        sink.write(path, content, append);
      } catch (IOException retryFailure) {
        throw new UncheckedIOException(message + "（中断重试仍失败）", retryFailure);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(message, e);
    }
  }

  private static void writeFile(Path path, String content, boolean append) throws IOException {
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

  private Path briefPath(AgentBrief brief, String suffix) {
    // M2 角色标识含斜杠（bug-hunter/dataflow，03§2.2）——文件名内归一为下划线，避免拼出子目录
    String roleId = brief.role().id().replace('/', '_');
    // chunk key 为路径前两段（如 cra-runner-cli/src，02§4.3 模块分组）——文件名归一防拼出子目录
    // （M2 验收 8 pre-PR 实跑发现：多级目录 chunk 时 brief 落盘失败 → 全部代理隔离失败）
    String safeSuffix = suffix == null ? null : suffix.replace('/', '_').replace('\\', '_');
    String name =
        safeSuffix == null || safeSuffix.isBlank()
            ? roleId + ".md"
            : roleId + "-" + safeSuffix + ".md";
    return runDir.resolve("briefs").resolve(name);
  }
}
