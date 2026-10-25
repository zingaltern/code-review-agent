package org.cra.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 模板装载器单测（08§2.3：工程测试只断言渲染后的结构——定界完整、占位符全展开）。 */
class TemplateLoaderTest {

  private final TemplateLoader loader = new TemplateLoader();

  @Test
  void rendersPlaceholdersAndIncludes() {
    String out =
        loader.render(
            "prompts/reviewer/bug-hunter/boundary.md",
            Map.of(
                "untrusted_pr_meta", "标题",
                "review_policy_section", "策略",
                "untrusted_diff", "diff 内容",
                "untrusted_l1_context", "窗口上下文",
                "scoped_paths_note", ""));
    assertThat(out).startsWith("# System");
    assertThat(out).contains("# User");
    assertThat(out).contains("<<<UNTRUSTED_CONTENT");
    assertThat(out).contains("标题");
    assertThat(out).contains("diff 内容");
    assertThat(out).doesNotContain("{{");
    assertThat(out).doesNotContain("{>");
  }

  @Test
  void missingVarThrows() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                loader.render(
                    "prompts/reviewer/bug-hunter/boundary.md", Map.of("untrusted_pr_meta", "x")));
  }

  @Test
  void unknownTemplateThrows() {
    assertThatThrownBy(() -> loader.render("prompts/none.md", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void versionIsStableContentHash() {
    String v1 = loader.versionOf("prompts/reviewer/bug-hunter/boundary.md");
    String v2 = loader.versionOf("prompts/reviewer/bug-hunter/boundary.md");
    assertThat(v1).isEqualTo(v2);
    assertThat(v1).startsWith("boundary@").containsPattern("@[0-9a-f]{8}");
  }

  @Test
  void contentBracesDoNotConfusePlaceholders() {
    // 不可信内容含 {word} 不得被误当占位符（双花括号语法的存在理由）
    String out =
        loader.render(
            "prompts/reviewer/bug-hunter/boundary.md",
            Map.of(
                "untrusted_pr_meta", "return {if} {x} from map",
                "review_policy_section", "",
                "untrusted_diff", "code {a} {{b}",
                "untrusted_l1_context", "ctx {c}",
                "scoped_paths_note", ""));
    assertThat(out).contains("return {if} {x} from map");
    assertThat(out).contains("code {a} {{b}");
  }
}
