package org.cra.server.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * GitLab Merge Request Hook 解析（ADR-0013；02§5 触发边界的 GitLab 映射）。
 *
 * <p>只评审会改变可评审内容的动作：`open` / `reopen` / 带新提交的 `update`（GitLab 用 `oldrev` 非空表示新提交，对应 GitHub 的
 * `synchronize`）。`merge` / `close` / 审批类 / 仅元数据更新 / draft 明确忽略并返回原因——不静默假装支持，也不在 HTTP 层返回错误（GitLab
 * 会重投非 2xx）。
 *
 * <p>事件 ID 幂等键：优先 `X-Gitlab-Event-UUID`；缺失时用 `SHA-256(event + body)` 的确定性摘要（同一 payload 重投命中同一
 * delivery）。
 */
@Component
public final class GitLabMergeRequestEventParser {

  /** GitLab webhook 事件头取值（Merge Request Hook）。 */
  public static final String EVENT_NAME = "Merge Request Hook";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OBJECT_KIND = "merge_request";

  /**
   * 解析结果：accepted 时 event 非空；ignored 时 reason 非空（显式声明原因）。
   *
   * @param event 需评审的事件
   * @param reason 忽略原因（unsupported_event / merged / draft 等）
   */
  public record Result(PullRequestEvent event, String reason) {

    /** 是否需评审。 */
    public boolean accepted() {
      return event != null;
    }

    static Result accepted(PullRequestEvent event) {
      return new Result(event, null);
    }

    static Result ignored(String reason) {
      return new Result(null, reason);
    }
  }

  /**
   * 解析 `X-Gitlab-Event` + payload。
   *
   * @param eventName X-Gitlab-Event
   * @param eventUuid X-Gitlab-Event-UUID（可空）
   * @param body 原始请求体
   * @return 解析结果
   * @throws IllegalArgumentException payload 非法（HTTP 400）
   */
  public Result parse(String eventName, String eventUuid, byte[] body) {
    if (!EVENT_NAME.equals(eventName)) {
      return Result.ignored("unsupported_event");
    }
    JsonNode root = parseRoot(body);
    JsonNode attributes = root.get("object_attributes");
    if (attributes == null || !attributes.isObject()) {
      throw new IllegalArgumentException("merge_request payload 缺 object_attributes");
    }
    String objectKind = textOrNull(root, "object_kind");
    if (objectKind != null && !OBJECT_KIND.equals(objectKind)) {
      return Result.ignored("unsupported_object_kind");
    }
    String action = text(attributes, "action");
    Result ignored = ignoredByState(attributes, action);
    if (ignored != null) {
      return ignored;
    }
    String normalizedAction = normalizeAction(action, attributes);
    if (normalizedAction == null) {
      return Result.ignored(unsupportedActionReason(action));
    }
    if (isDraft(attributes)) {
      return Result.ignored("draft");
    }
    DiffShas shas = requireDiffShas(attributes);
    String projectPath = requireProjectPath(root);
    int lastSlash = projectPath.lastIndexOf('/');
    int iid = requireIid(attributes);
    String deliveryId = deliveryId(eventName, eventUuid, body);
    return Result.accepted(
        new PullRequestEvent(
            deliveryId,
            normalizedAction,
            projectPath.substring(0, lastSlash),
            projectPath.substring(lastSlash + 1),
            iid,
            shas.baseSha(),
            shas.headSha()));
  }

  /**
   * 解析请求体 JSON（非法 JSON → 400）。
   *
   * @param body 原始请求体
   * @return 根节点
   */
  private static JsonNode parseRoot(byte[] body) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("webhook body 不是合法 JSON: " + e.getMessage());
    }
  }

  /**
   * merged/closed 状态忽略（02§ webhook 幂等：终态事件不入评审队列）。
   *
   * @param attributes object_attributes
   * @param action 原始 action
   * @return 忽略结果；null = 继续处理
   */
  private static Result ignoredByState(JsonNode attributes, String action) {
    String state = textOrNull(attributes, "state");
    if ("merged".equals(state) || "merge".equals(action)) {
      return Result.ignored("merged");
    }
    if ("closed".equals(state) || "close".equals(action)) {
      return Result.ignored("closed");
    }
    return null;
  }

  /**
   * 不可评审 action 的忽略原因。
   *
   * @param action 原始 action
   * @return 忽略原因
   */
  private static String unsupportedActionReason(String action) {
    if ("update".equals(action)) {
      return "metadata_update";
    }
    if (action.startsWith("approv") || action.startsWith("unapprov")) {
      return "approval_event";
    }
    return "unsupported_action";
  }

  /**
   * 取 base/head sha（diff_refs，head 可回退 last_commit.id）。
   *
   * @param attributes object_attributes
   * @return sha 对
   */
  private static DiffShas requireDiffShas(JsonNode attributes) {
    JsonNode diffRefs = attributes.path("diff_refs");
    String baseSha = textOrNull(diffRefs, "base_sha");
    String headSha =
        firstNonBlank(
            textOrNull(diffRefs, "head_sha"), textOrNull(attributes.path("last_commit"), "id"));
    if (baseSha == null || headSha == null) {
      throw new IllegalArgumentException(
          "merge_request payload 缺 diff_refs.base_sha/head_sha（无法确定评审基线）");
    }
    return new DiffShas(baseSha, headSha);
  }

  /**
   * 取并校验 project.path_with_namespace（group/project）。
   *
   * @param root 根节点
   * @return 项目路径
   */
  private static String requireProjectPath(JsonNode root) {
    String projectPath = textOrNull(root.path("project"), "path_with_namespace");
    if (projectPath == null) {
      throw new IllegalArgumentException("merge_request payload 缺 project.path_with_namespace");
    }
    int lastSlash = projectPath.lastIndexOf('/');
    if (lastSlash <= 0 || lastSlash == projectPath.length() - 1) {
      throw new IllegalArgumentException(
          "project.path_with_namespace 形如 group/project: " + projectPath);
    }
    return projectPath;
  }

  /**
   * 取并校验 MR iid。
   *
   * @param attributes object_attributes
   * @return iid
   */
  private static int requireIid(JsonNode attributes) {
    int iid = attributes.path("iid").asInt(0);
    if (iid <= 0) {
      throw new IllegalArgumentException("merge_request payload 缺合法 iid: " + iid);
    }
    return iid;
  }

  /**
   * 投递 ID：优先 X-Gitlab-Event-UUID，缺失时按 payload 生成确定性摘要。
   *
   * @param eventName X-Gitlab-Event
   * @param eventUuid X-Gitlab-Event-UUID（可空）
   * @param body 原始请求体
   * @return 投递 ID
   */
  private static String deliveryId(String eventName, String eventUuid, byte[] body) {
    return eventUuid != null && !eventUuid.isBlank()
        ? eventUuid
        : fallbackDeliveryId(eventName, body);
  }

  /**
   * base/head sha 对。
   *
   * @param baseSha 基线 sha
   * @param headSha 目标 sha
   */
  private record DiffShas(String baseSha, String headSha) {}

  /**
   * 事件 ID 回退：确定性摘要，保证同一 payload 重投命中同一 delivery。
   *
   * @param eventName X-Gitlab-Event
   * @param body 原始请求体
   * @return `gitlab-` + 40 位十六进制
   */
  static String fallbackDeliveryId(String eventName, byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(eventName.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(body == null ? new byte[0] : body);
      return "gitlab-" + HexFormat.of().formatHex(digest.digest()).substring(0, 40);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private static String normalizeAction(String action, JsonNode attributes) {
    return switch (action) {
      case "open" -> "opened";
      case "reopen" -> "reopened";
      case "update" -> textOrNull(attributes, "oldrev") == null ? null : "synchronize";
      default -> null;
    };
  }

  private static boolean isDraft(JsonNode attributes) {
    if (attributes.path("draft").asBoolean(false)
        || attributes.path("work_in_progress").asBoolean(false)) {
      return true;
    }
    String title = textOrNull(attributes, "title");
    if (title == null) {
      return false;
    }
    String lower = title.strip().toLowerCase(Locale.ROOT);
    return lower.startsWith("draft:")
        || lower.startsWith("[draft]")
        || lower.startsWith("(draft)")
        || lower.startsWith("wip:")
        || lower.startsWith("[wip]")
        || lower.startsWith("(wip)");
  }

  private static String text(JsonNode node, String field) {
    String value = textOrNull(node, field);
    if (value == null) {
      throw new IllegalArgumentException("merge_request payload 缺字段: " + field);
    }
    return value;
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() || value.asText().isBlank()
        ? null
        : value.asText();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
