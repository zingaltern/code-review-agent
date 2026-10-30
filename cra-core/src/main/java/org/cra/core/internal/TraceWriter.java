package org.cra.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.cra.agents.api.AgentBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * trace 写入器（05§1.4 NDJSON）：.cra/runs/&lt;run_id&gt;/trace.ndjson + briefs/*.md（brief 全文随 trace 存于同
 * artifact——M0 验收 4）。事件集对齐 05§1.4，M0 新增编排步骤事件先行（07§6.5）。
 */
public final class TraceWriter {

  private static final Logger LOG = LoggerFactory.getLogger(TraceWriter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path runDir;
  private final String runId;
  private final Object lock = new Object();

  /**
   * 写入可用性：trace 是观测面，磁盘故障不得让 run 契约（返回 ReviewResult）失效——降级为只告警一次， 由 WARN 日志承担"trace
   * 缺失"的可见性（09§4：边界内收敛故障）。
   */
  private volatile boolean writable = true;

  public TraceWriter(Path runRoot, String runId) {
    this.runId = runId;
    this.runDir = runRoot.resolve(runId);
    try {
      Files.createDirectories(runDir.resolve("briefs"));
    } catch (IOException e) {
      writable = false;
      LOG.warn("trace 目录创建失败（本次 run 的 trace/brief 不可写）: {}（{}）", runDir, e.getMessage());
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
   */
  public void emit(String event, Map<String, Object> fields) {
    if (!writable) {
      return;
    }
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
        writable = false;
        LOG.warn("trace 写入失败，后续事件跳过（run_id={}）: {}", runId, e.getMessage());
      }
    }
  }

  /**
   * brief 全文落盘（system + user 分段）。
   *
   * @param brief 自包含简报
   * @return brief 文件路径（进 agent_start 事件的 brief_path）
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
   */
  public Path persistBrief(AgentBrief brief, String suffix) {
    Path path = briefPath(brief, suffix);
    if (!writable) {
      return path;
    }
    String content = "# system\n" + brief.systemPrompt() + "\n\n# user\n" + brief.userPrompt();
    try {
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      writable = false;
      LOG.warn("brief 落盘失败，后续 brief 跳过（run_id={}）: {}", runId, e.getMessage());
    }
    return path;
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
