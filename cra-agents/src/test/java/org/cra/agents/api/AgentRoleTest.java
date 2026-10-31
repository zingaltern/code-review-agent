package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** AgentRole 标识校验（05§1.1 kebab-case）：合法视角族通过，首尾/连续分隔符与空白展示名拒绝。 */
class AgentRoleTest {

  @Test
  void acceptsKebabCaseRoleIds() {
    assertThat(new AgentRole("judge", "终裁仲裁员").id()).isEqualTo("judge");
    assertThat(new AgentRole("bug-hunter/dataflow", "BugHunter").id())
        .isEqualTo("bug-hunter/dataflow");
  }

  @Test
  void rejectsMalformedRoleIds() {
    for (String id : List.of("-", "/", "a//b", "a/", "/a", "Bug-Hunter", "a b", "")) {
      assertThatThrownBy(() -> new AgentRole(id, "x"))
          .as("id=%s", id)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("kebab-case");
    }
  }

  @Test
  void rejectsBlankDisplayName() {
    assertThatThrownBy(() -> new AgentRole("judge", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("displayName");
  }
}
