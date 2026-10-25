package org.cra.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cra.agents.api.AgentBrief;

/**
 * trace 写入器（05§1.4 NDJSON）：.cra/runs/&lt;run_id&gt;/trace.ndjson + briefs/*.md（brief 全文随 trace 存于同
 * artifact——M0 验收 4）。事件集对齐 05§1.4，M0 新增编排步骤事件先行（07§6.5）。
 */
final class TraceWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path runDir;
  private final String runId;
  private final Object lock = new Object();

  TraceWriter(Path runRoot, String runId) {
    this.runId = runId;
    this.runDir = runRoot.resolve(runId);
    try {
      Files.createDirectories(runDir.resolve("briefs"));
    } catch (IOException e) {
      throw new UncheckedIOException("trace 目录创建失败: " + runDir, e);
    }
  }

  Path runDir() {
    return runDir;
  }

  /**
   * 追加一条事件（ts/run_id/event 前置，字段顺序即文档形态）。
   *
   * @param event 事件名（05§1.4，snake_case）
   * @param fields 事件字段
   */
  void emit(String event, Map<String, Object> fields) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("ts", Instant.now().toString());
    node.put("run_id", runId);
    node.put("event", event);
    fields.forEach((k, v) -> node.set(k, MAPPER.valueToTree(v)));
    synchronized (lock) {
      try {
        Files.writeString(
            runDir.resolve("trace.ndjson"),
            node + "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("trace 写入失败", e);
      }
    }
  }

  /**
   * brief 全文落盘（system + user 分段）。
   *
   * @param brief 自包含简报
   * @return brief 文件路径（进 agent_start 事件的 brief_path）
   */
  Path persistBrief(AgentBrief brief) {
    return persistBrief(brief, null);
  }

  /**
   * brief 全文落盘（M1 分 chunk 形态：同角色多 chunk 以后缀区分文件）。
   *
   * @param brief 自包含简报
   * @param suffix 文件名后缀（如 chunk 序号），null 时用角色 id
   * @return brief 文件路径
   */
  Path persistBrief(AgentBrief brief, String suffix) {
    Map<String, Object> fields = new LinkedHashMap<>();
    String name =
        suffix == null || suffix.isBlank()
            ? brief.role().id() + ".md"
            : brief.role().id() + "-" + suffix + ".md";
    Path path = runDir.resolve("briefs").resolve(name);
    String content = "# system\n" + brief.systemPrompt() + "\n\n# user\n" + brief.userPrompt();
    try {
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("brief 落盘失败: " + path, e);
    }
    fields.put("path", path.toString());
    return path;
  }
}
