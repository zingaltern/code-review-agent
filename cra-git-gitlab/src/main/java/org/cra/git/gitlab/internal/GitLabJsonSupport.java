package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;

/** GitLab JSON 字段容错辅助（纯函数；适配器与账本读侧共用）。 */
final class GitLabJsonSupport {

  private GitLabJsonSupport() {}

  /** 取文本字段；节点缺失/null/空白一律返回 null（避免把空串当有效值）。 */
  static String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text.isBlank() ? null : text;
  }
}
