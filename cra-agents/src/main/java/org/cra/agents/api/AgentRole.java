package org.cra.agents.api;

import java.util.Objects;
import java.util.regex.Pattern;
import org.cra.common.text.Texts;

/**
 * 子代理角色标识（03§1；角色标识符 kebab-case，05§1.1——如 general-reviewer、bug-hunter/dataflow）。
 *
 * @param id 角色标识符（kebab-case，进 trace 与日志的 agent 字段）
 * @param displayName 展示名
 */
public record AgentRole(String id, String displayName) {

  private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(?:[-/][a-z0-9]+)*");

  public AgentRole {
    Objects.requireNonNull(id, "id");
    if (!KEBAB_CASE.matcher(id).matches()) {
      throw new IllegalArgumentException("role id must be kebab-case: " + id);
    }
    Texts.requireNonBlank(displayName, "displayName");
  }
}
