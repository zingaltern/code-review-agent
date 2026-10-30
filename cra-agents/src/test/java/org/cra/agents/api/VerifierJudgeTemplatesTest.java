package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/**
 * M2 骨架占位模板冒烟测试（04§4.2 balanced-review / 04§4 目录树 refute-oriented / 03§4.4 judge）：
 * 模板可装载、占位符齐全、UNTRUSTED 定界与反锚定约束在位、版本哈希稳定。 消费者（VerifierAgent T2.4 / JudgeAgent
 * T2.6）由验证线落地，本测试保证模板先于实现可用。
 */
class VerifierJudgeTemplatesTest {

  private static final TemplateLoader LOADER = new TemplateLoader();

  private static final Map<String, String> VERIFIER_VARS =
      Map.of(
          "untrusted_findings", TemplateSupport.untrusted("finding 全文"),
          "untrusted_code_context", TemplateSupport.untrusted("file content"),
          "untrusted_cited_evidence", TemplateSupport.untrusted("commit diff"),
          "policy_sections", "必须引用 diff 行号",
          "untrusted_test_file_index", TemplateSupport.untrusted("- src/test/A.java"),
          "layer_contract_section", "",
          "followup_section", "");

  private static final Map<String, String> REFUTE_VARS =
      Map.of(
          "untrusted_findings_full", TemplateSupport.untrusted("finding 全文"),
          "untrusted_code_context", TemplateSupport.untrusted("file content"),
          "evidence_requirements", "必须引用 diff 行号");

  private static final Map<String, String> JUDGE_VARS =
      Map.of(
          "case_reason", TemplateSupport.untrusted("仲裁原因"),
          "untrusted_case", TemplateSupport.untrusted("finding 全文"),
          "untrusted_verifier", TemplateSupport.untrusted("verdict: CONFIRMED…"),
          "untrusted_context", TemplateSupport.untrusted("case code"),
          "followup_section", "");

  @Test
  void balancedReviewTemplateRendersWithAntiAnchorContract() {
    String rendered = LOADER.render("prompts/verifier/balanced-review.md", VERIFIER_VARS);
    assertThat(rendered).contains("独立复核员");
    assertThat(rendered).contains("CONFIRMED");
    assertThat(rendered).contains("REFUTED");
    assertThat(rendered).contains("INSUFFICIENT_EVIDENCE");
    // 反锚定输出约束（03§4.2）
    assertThat(rendered).contains("不得照抄结论中的引用");
    assertThat(rendered).contains("未出现的代码行引用");
    // 不可信定界与工具协议
    assertThat(rendered).contains("UNTRUSTED_CONTENT");
    assertThat(rendered).contains("followup_file");
    assertThat(LOADER.versionOf("prompts/verifier/balanced-review.md"))
        .startsWith("balanced-review@");
  }

  @Test
  void refuteOrientedTemplateMarksM3Gate() {
    String rendered = LOADER.render("prompts/verifier/refute-oriented.md", REFUTE_VARS);
    assertThat(rendered).contains("证伪导向");
    assertThat(rendered).contains("M3 占位");
    assertThat(rendered).contains("require-distinct-model-family");
    assertThat(rendered).contains("UNTRUSTED_CONTENT");
  }

  @Test
  void judgeArbitrateTemplateRendersWithOneShotRule() {
    String rendered = LOADER.render("prompts/judge/arbitrate.md", JUDGE_VARS);
    assertThat(rendered).contains("终裁仲裁员");
    assertThat(rendered).contains("不折中、不求共识");
    // 验证输出同样 UNTRUSTED（04§4.3）
    assertThat(rendered).contains("同样是被评审数据，独立对待");
    assertThat(LOADER.versionOf("prompts/judge/arbitrate.md")).startsWith("arbitrate@");
  }

  @Test
  void templateVersionsAreStableAcrossLoads() {
    String first = LOADER.versionOf("prompts/verifier/balanced-review.md");
    String second = new TemplateLoader().versionOf("prompts/verifier/balanced-review.md");
    assertThat(first).isEqualTo(second);
  }
}
