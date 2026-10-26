package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/** M4f-lite CrossChecker 定义（03§3.1/03§6）：预算/工具/档位、两模式模板与信任定界。 */
class CrossCheckerDefinitionTest {

  private final CrossCheckerDefinition definition =
      new CrossCheckerDefinition(new TemplateLoader());

  private static ReviewAgentInput input(String history, String trustedSections) {
    return new ReviewAgentInput(
        "PR #7",
        "c1 fix: something",
        "@@ -1,3 +1,4 @@\n-old\n+new\n context",
        "",
        "",
        history,
        trustedSections,
        Set.of("src/Foo.java"));
  }

  @Test
  void budgetToolsAndTierFollowSpecTable() {
    // 03§6 CrossChecker 行：≤16k in / ≤1.5k out / 2 轮 / read_file(scoped) / 标准档
    assertThat(definition.role().id()).isEqualTo("cross-checker");
    assertThat(definition.budget()).isEqualTo(new AgentBudget(16_000, 4_000, 2, 240_000));
    assertThat(definition.tools()).isEqualTo(ToolWhitelist.READ_FILE);
    assertThat(definition.modelTier()).isEqualTo(AgentTier.STANDARD);
  }

  @Test
  void historyIsUntrustedButProductSectionsAreTrusted() {
    String history = "abc1234 企业长会话定制（2026-09-01）";
    String trusted = "### Known incidents\n- 2026-08-14 会话超时事故：隐含假设被六周前企业定制破坏";

    AgentBrief brief = definition.buildBrief(input(history, trusted));

    assertThat(brief.systemPrompt()).contains("矛盾来源").contains("commit <");
    String user = brief.userPrompt();
    // 变更史经 UNTRUSTED 定界（04§3.3）；REVIEW.md 产品段在定界之外（可信，04§3.2）
    int historyStart = user.indexOf(history);
    int barrierBefore = user.lastIndexOf(TemplateSupport.UNTRUSTED_OPEN, historyStart);
    int barrierAfter = user.indexOf(TemplateSupport.UNTRUSTED_CLOSE, historyStart);
    assertThat(barrierBefore).isPositive();
    assertThat(barrierAfter).isGreaterThan(historyStart);
    int trustedStart = user.indexOf(trusted);
    assertThat(trustedStart).isGreaterThan(barrierAfter);
    assertThat(user.indexOf(TemplateSupport.UNTRUSTED_OPEN, trustedStart)).isNegative();
  }

  @Test
  void missingHistoryAndSectionsRenderExplicitDegradationNotes() {
    AgentBrief brief = definition.buildBrief(input("", ""));
    assertThat(brief.userPrompt()).contains("变更史不可得").contains("REVIEW.md 未配置 Product context");
    assertThat(brief.userPrompt()).contains("src/Foo.java");
  }
}
