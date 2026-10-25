package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** LLM JSON 输出解析单测（04§6：枚举拒收是行为、错误携出供重试、id/run 由系统生成）。 */
class FindingJsonCodecTest {

  @Test
  void parsesValidFinding() {
    var result =
        FindingJsonCodec.parse(
            """
        {"findings":[{"category":"context","title":"会话超时假设矛盾",
          "description":"d","failure_mode":"f","file":"a.java","line":3,"side":"RIGHT",
          "evidence":"e","suggestion":"s","severity_proposed":"important",
          "pre_existing_suspect":true,"confidence":0.9}]}""",
            "run_1",
            "general-reviewer",
            "glm-x");
    assertThat(result.errors()).isEmpty();
    assertThat(result.findings()).hasSize(1);
    var f = result.findings().get(0);
    assertThat(f.category().name()).isEqualTo("CONTEXT"); // 枚举含 context（05§1.1 v1.3.1）
    assertThat(f.id()).startsWith("f_");
    assertThat(f.runId()).isEqualTo("run_1");
    assertThat(f.originAgentRole()).isEqualTo("general-reviewer");
    assertThat(f.originModel()).isEqualTo("glm-x");
    assertThat(f.side().name()).isEqualTo("RIGHT");
  }

  @Test
  void emptyFindingsArrayIsValid() {
    var result = FindingJsonCodec.parse("{\"findings\":[]}", "run_1", "r", "m");
    assertThat(result.findings()).isEmpty();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void malformedRootJsonIsSingleError() {
    var result = FindingJsonCodec.parse("no json", "run_1", "r", "m");
    assertThat(result.findings()).isEmpty();
    assertThat(result.errors()).hasSize(1).first().asString().contains("非合法 JSON");
  }

  @Test
  void missingFindingsArrayIsError() {
    var result = FindingJsonCodec.parse("{\"x\":1}", "run_1", "r", "m");
    assertThat(result.errors()).hasSize(1).first().asString().contains("findings");
  }

  @Test
  void unknownCategoryRejected() {
    var result =
        FindingJsonCodec.parse(
            """
        {"findings":[{"category":"style","title":"t","description":"d","failure_mode":"f",
          "file":"a","line":1,"evidence":"e","severity_proposed":"nit"}]}""",
            "run_1",
            "r",
            "m");
    assertThat(result.findings()).isEmpty();
    assertThat(result.errors()).first().asString().contains("finding[0]");
  }

  @Test
  void missingFieldRejected() {
    var result =
        FindingJsonCodec.parse(
            """
        {"findings":[{"category":"bug","title":"t","description":"d","failure_mode":"f",
          "file":"a","line":1,"severity_proposed":"nit"}]}""",
            "run_1",
            "r",
            "m");
    assertThat(result.findings()).isEmpty();
    assertThat(result.errors()).first().asString().contains("evidence");
  }

  @Test
  void badLineAndConfidenceRejected() {
    var result =
        FindingJsonCodec.parse(
            """
        {"findings":[{"category":"bug","title":"t","description":"d","failure_mode":"f",
          "file":"a","line":0,"evidence":"e","severity_proposed":"nit","confidence":2.0}]}""",
            "run_1",
            "r",
            "m");
    assertThat(result.findings()).isEmpty();
    assertThat(result.errors()).hasSize(1);
  }

  @Test
  void partialValidStillCarriesValidOnes() {
    var result =
        FindingJsonCodec.parse(
            """
        {"findings":[
          {"category":"bug","title":"ok","description":"d","failure_mode":"f","file":"a",
           "line":1,"evidence":"e","severity_proposed":"nit"},
          {"category":"bug","title":"缺证据","description":"d","failure_mode":"f","file":"a",
           "line":2,"severity_proposed":"nit"}]}""",
            "run_1",
            "r",
            "m");
    assertThat(result.findings()).hasSize(1);
    assertThat(result.errors()).hasSize(1);
  }
}
