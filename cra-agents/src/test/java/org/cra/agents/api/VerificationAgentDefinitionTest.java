package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/**
 * VerifierAgent / JudgeAgent 定义单测（07 T2.4/T2.6；04§4.2/§4.3）：模板渲染严格替换、UNTRUSTED 定界、 预算对齐 03§6
 * 速查表、模板版本入 brief（05§1.2 关联点）。LLM 调用不在此层（08§3.1）。
 */
class VerificationAgentDefinitionTest {

  private final VerifierAgentDefinition verifier =
      new VerifierAgentDefinition(new TemplateLoader());
  private final JudgeAgentDefinition judge = new JudgeAgentDefinition(new TemplateLoader());

  private static VerificationItem item() {
    return new VerificationItem(
        "f_01",
        "bug",
        "important",
        "空列表越界",
        "items 为空时取末元素越界",
        "触发条件：空列表；错误结果：IndexOutOfBoundsException",
        "L4: return items.get(items.size() - 1);",
        null);
  }

  @Test
  void verifierBriefCarriesThreeValuedContractAndUntrustedSections() {
    AgentBrief brief =
        verifier.buildBrief(
            new VerificationBatchInput(
                "src/Foo.java",
                List.of(item()),
                "public class Foo { … }",
                "",
                "## Evidence requirements\n- 必须给出触发输入",
                ""));
    assertThat(brief.role().id()).isEqualTo("verifier");
    assertThat(brief.modelTier()).isEqualTo(AgentTier.STRONG);
    assertThat(brief.systemPrompt())
        .contains("CONFIRMED")
        .contains("REFUTED")
        .contains("INSUFFICIENT_EVIDENCE")
        .contains("反锚定");
    assertThat(brief.systemPrompt()).contains("\"verdicts\"");
    assertThat(brief.userPrompt())
        .contains(TemplateSupport.UNTRUSTED_OPEN)
        .contains(TemplateSupport.UNTRUSTED_CLOSE)
        .contains("空列表越界")
        .contains("f_01")
        .contains("必须给出触发输入");
    assertThat(brief.userPrompt()).doesNotContain("{{");
  }

  @Test
  void verifierFollowupRoundInjectsRequestedFileText() {
    AgentBrief brief =
        verifier.buildBrief(
            new VerificationBatchInput(
                "src/Foo.java",
                List.of(item()),
                "code",
                "",
                "",
                "class Helper { void check() {} }"));
    assertThat(brief.userPrompt()).contains("补查材料").contains("class Helper");
  }

  @Test
  void verifierAndJudgeUseStrongTierPerSpecTable() {
    // 03§6 速查表：VerifierAgent / JudgeAgent 均为「强」档；档位经 brief 透传到 AgentRunner 路由
    assertThat(new VerifierAgentDefinition(new TemplateLoader()).modelTier())
        .isEqualTo(AgentTier.STRONG);
    assertThat(new JudgeAgentDefinition(new TemplateLoader()).modelTier())
        .isEqualTo(AgentTier.STRONG);
  }

  @Test
  void verifierBudgetMatchesSpecRow() {
    // 03§6（AI Coding 放宽档，ADR-0015 §2.2 修订）：VerifierAgent ≤16k 输入 / ≤2k 输出 / 2 轮
    assertThat(verifier.budget().maxInputTokens()).isEqualTo(32_000);
    assertThat(verifier.budget().maxOutputTokens()).isEqualTo(4_000);
    assertThat(verifier.budget().maxTurns()).isEqualTo(2);
    assertThat(verifier.tools().tools()).isEmpty();
  }

  @Test
  void judgeBriefSeparatesCaseAndVerifierVerdictAsUntrusted() {
    AgentBrief brief =
        judge.buildBrief(
            new JudgeCaseInput(
                "f_01",
                "bug",
                "important",
                "空列表越界",
                "描述",
                "触发条件：空列表",
                "L4: evidence",
                null,
                "REFUTED。理由：L3 已有防护",
                "public class Foo { … }",
                "情形：复核员无法判定，请你仲裁。"));
    assertThat(brief.role().id()).isEqualTo("judge");
    assertThat(brief.modelTier()).isEqualTo(AgentTier.STRONG);
    assertThat(brief.systemPrompt()).contains("终裁仲裁员").contains("不折中").contains("REJECTED");
    // 案件、验证者判定、代码上下文三段都在 UNTRUSTED 定界内（04§4.3：验证输出同样不可信）
    long opens =
        brief.userPrompt().lines().filter(l -> l.contains(TemplateSupport.UNTRUSTED_OPEN)).count();
    assertThat(opens).isGreaterThanOrEqualTo(3);
    assertThat(insideUntrusted(brief.userPrompt(), "REFUTED。理由")).isTrue();
    assertThat(insideUntrusted(brief.userPrompt(), "情形：复核员无法判定")).isFalse();
    assertThat(brief.userPrompt()).contains("L3 已有防护").contains("情形：复核员无法判定");
    assertThat(brief.userPrompt()).doesNotContain("{{");
  }

  private static boolean insideUntrusted(String text, String needle) {
    int at = text.indexOf(needle);
    if (at < 0) {
      return false;
    }
    int open = text.lastIndexOf(TemplateSupport.UNTRUSTED_OPEN, at);
    int close = text.lastIndexOf(TemplateSupport.UNTRUSTED_CLOSE, at);
    return open > close;
  }

  @Test
  void judgeBudgetMatchesSpecRow() {
    // 03§6（AI Coding 放宽档）：JudgeAgent ≤20k 输入 / ≤1.5k 输出 / 2 轮
    assertThat(judge.budget().maxInputTokens()).isEqualTo(40_000);
    assertThat(judge.budget().maxOutputTokens()).isEqualTo(3_000);
    assertThat(judge.budget().maxTurns()).isEqualTo(2);
  }

  @Test
  void templateVersionsAreHashedAndCarriedInBrief() {
    // 05§1.2：prompt_template_versions 与 run 关联——版本为模板内容哈希
    AgentBrief verifierBrief =
        verifier.buildBrief(new VerificationBatchInput("F.java", List.of(item()), "c", "", "", ""));
    assertThat(verifierBrief.templateVersion()).matches("balanced-review@[0-9a-f]{8}");
    AgentBrief judgeBrief =
        judge.buildBrief(
            new JudgeCaseInput(
                "f_01", "bug", "important", "t", "d", "f", "e", null, "REFUTED", "c", "情形"));
    assertThat(judgeBrief.templateVersion()).matches("arbitrate@[0-9a-f]{8}");
  }

  @Test
  void assemblyFactoriesProduceDefinitions() {
    assertThat(AgentsAssembly.createVerifier().role().id()).isEqualTo("verifier");
    assertThat(AgentsAssembly.createJudge().role().id()).isEqualTo("judge");
  }
}
