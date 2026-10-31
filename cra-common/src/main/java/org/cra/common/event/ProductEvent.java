package org.cra.common.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 产品事件史条目（M4f-full，ADR-0012；03§3.1 CrossChecker 模式 A 的外部证据面）。
 *
 * <p>数据来源是 owner 维护的 JSONL（本地文件或对象存储 HTTP(S) URL），只覆盖<strong>已文档化</strong>的工单 / 事故复盘 / 变更记录；tribal
 * knowledge（口头约定、未记录的事故）不可见是硬边界（01§3.4），本模型不假装覆盖。
 *
 * <p>字段与 05§1.6 表 {@code product_event} 一一对应；{@code paths} 是可选扩展（事件源声明的仓库相对路径或目录前缀）， 用于按变更路径检索。幂等键
 * = (repo, event_id)。
 *
 * @param eventId 事件标识（源系统内唯一，如 {@code INC-42}；字符集见 {@link #EVENT_ID_PATTERN}）
 * @param repo 归属仓库（{@code owner/name}，统一小写存储与查询）
 * @param service 服务/模块（可空）
 * @param tenant 租户（可空；多租户产品的事故常只影响特定租户）
 * @param occurredAt 事件发生时间（窗口与增量水位依据）
 * @param type 事件类型（{@code ticket|incident|postmortem|change|other}，小写；postmortem 计入事故复盘）
 * @param summary 事件摘要（检索与 brief 渲染的唯一正文来源；raw_json 不进 brief）
 * @param sourceUrl 源系统链接（可空；人工核对用）
 * @param commitRefs 事件关联的 commit（小写十六进制，4–64 位；去重保序）
 * @param paths 事件影响的仓库相对路径或目录前缀（规范化见 {@link EventPathMatcher#normalize(String)}）
 * @param rawJson 源记录原始 JSON（紧凑序列化；只入库不注入 brief）
 * @param collectedAt 采集时间（采集器写入；同一事件的重复采集不改变首条记录的发生时间）
 */
public record ProductEvent(
    String eventId,
    String repo,
    String service,
    String tenant,
    Instant occurredAt,
    String type,
    String summary,
    String sourceUrl,
    List<String> commitRefs,
    List<String> paths,
    String rawJson,
    Instant collectedAt) {

  /** 事件标识字符集（与矛盾来源引用格式 {@link org.cra.common.finding.ContextCitation} 一致）。 */
  public static final Pattern EVENT_ID_PATTERN =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:#/-]{0,199}");

  /** commit 引用格式（小写十六进制；短 sha 与完整 sha 均可）。 */
  private static final Pattern COMMIT_REF_PATTERN = Pattern.compile("[0-9a-f]{4,64}");

  /** 摘要展示上限（字符；超出视为源数据非法——不静默截断，让采集失败可见）。 */
  private static final int SUMMARY_MAX_CHARS = 4_000;

  public ProductEvent {
    if (eventId == null || !EVENT_ID_PATTERN.matcher(eventId.strip()).matches()) {
      throw new IllegalArgumentException(
          "event_id 非法（" + EVENT_ID_PATTERN.pattern() + "）: " + eventId);
    }
    eventId = eventId.strip();
    if (repo == null || repo.isBlank()) {
      throw new IllegalArgumentException("repo 必填（owner/name）");
    }
    repo = repo.strip().toLowerCase(Locale.ROOT);
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurred_at 必填: " + eventId);
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type 必填: " + eventId);
    }
    type = type.strip().toLowerCase(Locale.ROOT);
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary 必填: " + eventId);
    }
    summary = summary.strip();
    if (summary.length() > SUMMARY_MAX_CHARS) {
      throw new IllegalArgumentException("summary 超长（>" + SUMMARY_MAX_CHARS + " 字符）: " + eventId);
    }
    service = blankToNull(service);
    tenant = blankToNull(tenant);
    sourceUrl = blankToNull(sourceUrl);
    commitRefs = normalizeCommitRefs(commitRefs, eventId);
    paths = normalizePaths(paths);
    rawJson = rawJson == null ? "" : rawJson;
    if (collectedAt == null) {
      throw new IllegalArgumentException("collected_at 必填: " + eventId);
    }
  }

  /**
   * 是否属于事故复盘类（统计用：incident + postmortem）。
   *
   * @return true 若 type 为 incident 或 postmortem
   */
  public boolean incidentLike() {
    return "incident".equals(type) || "postmortem".equals(type);
  }

  /**
   * 是否属于工单类（统计用）。
   *
   * @return true 若 type 为 ticket
   */
  public boolean ticketLike() {
    return "ticket".equals(type);
  }

  private static List<String> normalizeCommitRefs(List<String> refs, String eventId) {
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String ref : refs) {
      if (ref == null || ref.isBlank()) {
        continue;
      }
      String value = ref.strip().toLowerCase(Locale.ROOT);
      if (!COMMIT_REF_PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("commit_refs 含非十六进制引用: " + ref + "（事件 " + eventId + "）");
      }
      out.add(value);
    }
    return List.copyOf(out);
  }

  private static List<String> normalizePaths(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String path : paths) {
      String normalized = EventPathMatcher.normalize(path);
      if (!normalized.isEmpty()) {
        out.add(normalized);
      }
    }
    List<String> sorted = new ArrayList<>(out);
    sorted.sort(String::compareTo);
    return List.copyOf(sorted);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
