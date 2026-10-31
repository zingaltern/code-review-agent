package org.cra.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.cra.common.context.ReviewPolicy;
import org.cra.context.api.ReviewPolicyParser;
import org.junit.jupiter.api.Test;

/** REVIEW.md 解析行为（04§3.1 格式 + 04§3.2 规则 4 容错；07 T1.4）。 */
class DefaultReviewPolicyParserTest {

  private final ReviewPolicyParser parser = new DefaultReviewPolicyParser();

  @Test
  void parsesAllSectionsIncludingV13Three() {
    ReviewPolicy policy =
        parser.parse(
            """
            # Review Policy: repo

            ## What counts as failure
            - 所有对外 API 必须处理 LimitExceeded

            ## Nit budget: 3

            ## Priority paths
            - billing/**

            ## Skip paths
            - generated/**

            ## Evidence requirements
            - 必须引用 diff 行号

            ## Conventions
            - 禁止直接使用 Fastjson（用 Jackson）

            ## Product context
            - 后台任务默认在常规会话内跑完；企业客户走长会话定制

            ## Known incidents
            - 2026-08-14 会话超时事故：隐含假设被六周前企业定制破坏

            ## Sniff tests
            - 超时/重试类参数不得超过上游服务超时的 80%
            """);
    assertThat(policy.present()).isTrue();
    assertThat(policy.warnings()).isEmpty();
    assertThat(policy.nitBudget()).isEqualTo(3);
    assertThat(policy.priorityPaths()).containsExactly("billing/**");
    assertThat(policy.skipPaths()).containsExactly("generated/**");
    assertThat(policy.failureCriteria()).hasSize(1);
    assertThat(policy.evidenceRequirements()).containsExactly("必须引用 diff 行号");
    assertThat(policy.conventions()).hasSize(1);
    assertThat(policy.productContext()).hasSize(1);
    assertThat(policy.knownIncidents()).hasSize(1);
    assertThat(policy.sniffTests()).hasSize(1);
  }

  @Test
  void missingSectionsGracefullySkipped() {
    // v1.3 三新段落缺失 → 优雅跳过（07 T1.4：CrossChecker 相关检查优雅跳过的数据前提）
    ReviewPolicy policy =
        parser.parse("""
            ## Conventions
            - 禁止直接使用 Fastjson
            """);
    assertThat(policy.present()).isTrue();
    assertThat(policy.warnings()).isEmpty();
    assertThat(policy.conventions()).hasSize(1);
    assertThat(policy.productContext()).isEmpty();
    assertThat(policy.knownIncidents()).isEmpty();
    assertThat(policy.sniffTests()).isEmpty();
    assertThat(policy.nitBudget()).isNull();
  }

  @Test
  void nitBudgetVariants() {
    assertThat(parser.parse("## Nit budget: 0\n").nitBudget()).isZero();
    assertThat(parser.parse("## Nit budget\n\n3\n").nitBudget()).isEqualTo(3);
    ReviewPolicy invalid = parser.parse("## Nit budget: many\n");
    assertThat(invalid.nitBudget()).isNull();
    assertThat(invalid.warnings()).anyMatch(w -> w.contains("Nit budget 数值非法"));
    // 负数与非法文本同口径：警告 + 未配置（不走异常分支）
    ReviewPolicy negative = parser.parse("## Nit budget: -1\n");
    assertThat(negative.nitBudget()).isNull();
    assertThat(negative.warnings()).anyMatch(w -> w.contains("Nit budget 数值非法"));
  }

  @Test
  void unparseableContentDegradesToMinimalDefaultWithWarning() {
    // 04§3.2 规则 4：解析失败按最小默认策略运行 + 警告（不阻塞评审）
    ReviewPolicy policy = parser.parse("这只是一段没有标题结构的文字\n随便写点什么");
    assertThat(policy.present()).isTrue();
    assertThat(policy.effectiveNitBudget(3)).isEqualTo(3);
    assertThat(policy.warnings()).isNotEmpty();
  }

  @Test
  void blankAndNullAreEmptyPolicy() {
    assertThat(parser.parse(null).present()).isFalse();
    assertThat(parser.parse("   \n").present()).isFalse();
    assertThat(parser.parse("").warnings()).isEmpty();
  }

  @Test
  void unknownHeadingsIgnored() {
    ReviewPolicy policy = parser.parse("## Future section\n- something new\n## Conventions\n- x\n");
    assertThat(policy.warnings()).isEmpty();
    assertThat(policy.conventions()).containsExactly("x");
  }

  @Test
  void nestedHeadingDoesNotSplitSection() {
    ReviewPolicy policy =
        parser.parse(
            """
            ## Conventions
            - 使用 ### 强调
            - Jackson
            """);
    assertThat(policy.conventions()).containsExactly("使用 ### 强调", "Jackson");
  }
}
