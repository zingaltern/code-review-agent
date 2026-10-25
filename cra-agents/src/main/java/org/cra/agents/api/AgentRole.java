package org.cra.agents.api;

import java.util.Objects;

/**
 * 子代理角色标识（03§1；角色标识符 kebab-case，05§1.1——如 general-reviewer、bug-hunter/dataflow）。
 *
 * @param id 角色标识符（kebab-case，进 trace 与日志的 agent 字段）
 * @param displayName 展示名
 */
public record AgentRole(String id, String displayName) {

  public AgentRole {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
    if (id.isBlank() || !id.matches("[a-z0-9/-]+")) {
      throw new IllegalArgumentException("role id must be kebab-case: " + id);
    }
  }
}
