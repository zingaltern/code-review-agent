package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.cra.agents.api.AgentBudgetOverrides;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;
import org.junit.jupiter.api.Test;

/**
 * CraConfig Builder（05§5）：全组件往返、默认值语义、回填只改目标字段。 这三类用例替代原先的 30/34 参数位置构造——位置参数接错一格（尤其同类型 String
 * 字段）在这里必然显形。
 */
class CraConfigBuilderTest {

  /** AgentBudgetOverrides 无值相等（NONE 单例之外按引用比较），等值断言必须共用同一实例。 */
  private static final AgentBudgetOverrides BUDGETS =
      AgentBudgetOverrides.of(Map.of("judge", new AgentBudgetOverrides.RoleOverride(5000L, 800L)));

  /** 全组件互异取值：任何组件接错位置都会被 {@link #assertDistinctComponents} 打掉。 */
  private static CraConfig.Builder distinct() {
    return CraConfig.builder()
        .repoOwner("owner")
        .repoName("name")
        .prNumber(11)
        .baseSha("base-sha")
        .headSha("head-sha")
        .depth("deep")
        .dryRun(true)
        .securityBlock(true)
        .githubToken("gh-token")
        .githubApi("https://api.example")
        .llmApiKey("llm-key")
        .llmBaseUrl("https://llm.example/v1")
        .llmModel("model-standard")
        .runRoot("run-root")
        .workspace("workspace")
        .largeDiffLines(1234)
        .hardCapLines(5678)
        .nitBudget(7)
        .runTotalTokens(987_654L)
        .degradeAtFirst(0.61)
        .degradeAtSecond(0.91)
        .publishUnverified("labeled")
        .spotCheckPercent(33)
        .local(true)
        .execCommand("exec-cmd")
        .ledgerDb("ledger.db")
        .llmModelStrong("model-strong")
        .llmModelLight("model-light")
        .fallbackChain("strong,standard")
        .rpm("standard=11")
        .crossCheckerEnabled(true)
        .crossCheckerModes("sniff")
        .crossCheckerHistoryWeeks(5)
        .llmTemperature("0.2")
        .pushLookback(9)
        .reviewMode(ReviewMode.COMMIT)
        .signoff(new SignoffConfig(false, List.of("alice"), "bob", "passed"))
        .platform("gitlab")
        .gitlab(new GitLabSettings("https://gitlab.example/api/v4", "gl-token", "bot"))
        .semanticContext(new SemanticContextConfig(true, 500, 4096, 700))
        .includeTestFiles(true)
        .agentBudgets(BUDGETS);
  }

  @Test
  void builderSetsEveryComponent() {
    assertDistinctComponents(distinct().build());
  }

  @Test
  void builderDefaultsMatchDocumentedSemantics() {
    CraConfig defaults = CraConfig.builder().build();
    assertThat(defaults.pushLookback()).isEqualTo(CraConfig.DEFAULT_PUSH_LOOKBACK);
    assertThat(defaults.reviewMode()).isEqualTo(ReviewMode.PULL_REQUEST);
    assertThat(defaults.crossCheckerEnabled()).isFalse();
    assertThat(defaults.crossCheckerModes()).isEqualTo("assumption,sniff");
    assertThat(defaults.crossCheckerHistoryWeeks()).isEqualTo(8);
    assertThat(defaults.platform()).isEqualTo("github");
    assertThat(defaults.gitlab()).isEqualTo(GitLabSettings.none());
    assertThat(defaults.signoff()).isEqualTo(SignoffConfig.defaults());
    assertThat(defaults.semanticContext()).isEqualTo(SemanticContextConfig.defaults());
    assertThat(defaults.includeTestFiles()).isFalse();
    assertThat(defaults.agentBudgets()).isEqualTo(AgentBudgetOverrides.none());
  }

  @Test
  void toBuilderRoundTripPreservesAllComponents() {
    CraConfig base = distinct().build();
    assertThat(base.toBuilder().build()).isEqualTo(base);
  }

  @Test
  void withResolvedLocalOnlyReplacesIdentityAndRange() {
    CraConfig base = distinct().build();
    CraConfig resolved = base.withResolvedLocal("new-owner", "new-name", "new-base", "new-head");
    assertThat(resolved)
        .isEqualTo(
            distinct()
                .repoOwner("new-owner")
                .repoName("new-name")
                .baseSha("new-base")
                .headSha("new-head")
                .build());
  }

  @Test
  void withResolvedPushBaseOnlyReplacesBase() {
    CraConfig base = distinct().build();
    assertThat(base.withResolvedPushBase("push-base"))
        .isEqualTo(distinct().baseSha("push-base").build());
  }

  @Test
  void platformIsNormalizedAndValidated() {
    assertThat(CraConfig.builder().platform("  GITLAB ").build().platform()).isEqualTo("gitlab");
    assertThat(CraConfig.builder().platform("  GITLAB ").build().isGitlab()).isTrue();
    assertThatThrownBy(() -> CraConfig.builder().platform("bitbucket").build())
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("platform 非法");
  }

  /** 逐项断言（含全部 42 个组件；字符串互异，避免"接错但值相同"漏检）。 */
  private static void assertDistinctComponents(CraConfig c) {
    assertThat(c.repoOwner()).isEqualTo("owner");
    assertThat(c.repoName()).isEqualTo("name");
    assertThat(c.prNumber()).isEqualTo(11);
    assertThat(c.baseSha()).isEqualTo("base-sha");
    assertThat(c.headSha()).isEqualTo("head-sha");
    assertThat(c.depth()).isEqualTo("deep");
    assertThat(c.dryRun()).isTrue();
    assertThat(c.securityBlock()).isTrue();
    assertThat(c.githubToken()).isEqualTo("gh-token");
    assertThat(c.githubApi()).isEqualTo("https://api.example");
    assertThat(c.llmApiKey()).isEqualTo("llm-key");
    assertThat(c.llmBaseUrl()).isEqualTo("https://llm.example/v1");
    assertThat(c.llmModel()).isEqualTo("model-standard");
    assertThat(c.runRoot()).isEqualTo("run-root");
    assertThat(c.workspace()).isEqualTo("workspace");
    assertThat(c.largeDiffLines()).isEqualTo(1234);
    assertThat(c.hardCapLines()).isEqualTo(5678);
    assertThat(c.nitBudget()).isEqualTo(7);
    assertThat(c.runTotalTokens()).isEqualTo(987_654L);
    assertThat(c.degradeAtFirst()).isEqualTo(0.61);
    assertThat(c.degradeAtSecond()).isEqualTo(0.91);
    assertThat(c.publishUnverified()).isEqualTo("labeled");
    assertThat(c.spotCheckPercent()).isEqualTo(33);
    assertThat(c.local()).isTrue();
    assertThat(c.execCommand()).isEqualTo("exec-cmd");
    assertThat(c.ledgerDb()).isEqualTo("ledger.db");
    assertThat(c.llmModelStrong()).isEqualTo("model-strong");
    assertThat(c.llmModelLight()).isEqualTo("model-light");
    assertThat(c.fallbackChain()).isEqualTo("strong,standard");
    assertThat(c.rpm()).isEqualTo("standard=11");
    assertThat(c.crossCheckerEnabled()).isTrue();
    assertThat(c.crossCheckerModes()).isEqualTo("sniff");
    assertThat(c.crossCheckerHistoryWeeks()).isEqualTo(5);
    assertThat(c.llmTemperature()).isEqualTo("0.2");
    assertThat(c.pushLookback()).isEqualTo(9);
    assertThat(c.reviewMode()).isEqualTo(ReviewMode.COMMIT);
    assertThat(c.signoff()).isEqualTo(new SignoffConfig(false, List.of("alice"), "bob", "passed"));
    assertThat(c.platform()).isEqualTo("gitlab");
    assertThat(c.gitlab())
        .isEqualTo(new GitLabSettings("https://gitlab.example/api/v4", "gl-token", "bot"));
    assertThat(c.semanticContext()).isEqualTo(new SemanticContextConfig(true, 500, 4096, 700));
    assertThat(c.includeTestFiles()).isTrue();
    assertThat(c.agentBudgets().byRole().get("judge").inputTokens()).isEqualTo(5000L);
    assertThat(c.agentBudgets().byRole().get("judge").outputTokens()).isEqualTo(800L);
  }
}
