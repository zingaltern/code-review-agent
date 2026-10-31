package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 工具白名单（03§2.3）：常量、工厂、null 语义与空集归一。 */
class ToolWhitelistTest {

  @Test
  void noneAllowsNothing() {
    assertThat(ToolWhitelist.NONE.allows(ToolWhitelist.READ_FILE_NAME)).isFalse();
    assertThat(ToolWhitelist.NONE.allows(null)).isFalse();
  }

  @Test
  void readFileAllowsOnlyReadFile() {
    assertThat(ToolWhitelist.READ_FILE.allows(ToolWhitelist.READ_FILE_NAME)).isTrue();
    assertThat(ToolWhitelist.READ_FILE.allows(ToolWhitelist.GREP_PATTERN_NAME)).isFalse();
    assertThat(ToolWhitelist.READ_FILE.allows(null)).isFalse();
  }

  @Test
  void securityWhitelistAllowsBothTools() {
    assertThat(ToolWhitelist.READ_FILE_AND_GREP.allows(ToolWhitelist.READ_FILE_NAME)).isTrue();
    assertThat(ToolWhitelist.READ_FILE_AND_GREP.allows(ToolWhitelist.GREP_PATTERN_NAME)).isTrue();
  }

  @Test
  void nullSetNormalizesToEmpty() {
    assertThat(new ToolWhitelist(null).tools()).isEmpty();
  }

  @Test
  void factoryRejectsNullToolName() {
    assertThatThrownBy(() -> ToolWhitelist.of((String) null))
        .isInstanceOf(NullPointerException.class);
  }
}
