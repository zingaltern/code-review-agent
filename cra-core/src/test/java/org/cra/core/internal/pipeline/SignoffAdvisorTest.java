package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.signoff.RiskTier;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffContext;
import org.cra.common.signoff.SignoffRuleHit;
import org.cra.common.signoff.TestsStatus;
import org.cra.core.api.SignoffOptions;
import org.junit.jupiter.api.Test;

/** 风险分级三档与边界（07§5 M4a；01§3.4 分级表）：纯确定性规则，不调用 LLM。 */
class SignoffAdvisorTest {

  private static final String BASE = "aaa0000000000000000000000000000000000000";
  private static final String HEAD = "bbb1111111111111111111111111111111111111";
  private static final String PATCH = "@@ -1,2 +1,3 @@\n context\n+added\n-removed\n";

  private static final SignoffContext TRUSTED =
      new SignoffContext("zingaltern", TestsStatus.PASSED);

  private static ReviewRequest request(SignoffContext context) {
    return new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.STANDARD, false, false, context);
  }

  private static DiffResult diff(String... paths) {
    return diff(paths, PATCH);
  }

  private static DiffResult diff(String[] paths, String patch) {
    List<DiffFile> files =
        java.util.Arrays.stream(paths)
            .map(
                path ->
                    new DiffFile(path, null, "modified", patch, List.of(new DiffHunk(1, 2, 1, 3))))
            .toList();
    return new DiffResult(BASE, HEAD, files, List.of());
  }

  private static SignoffAdvice assess(
      SignoffOptions options, SignoffContext context, String... paths) {
    return new SignoffAdvisor(options).assess(request(context), diff(paths));
  }

  private static SignoffRuleHit rule(SignoffAdvice advice, String id) {
    return advice.rules().stream()
        .filter(hit -> hit.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing rule " + id + " in " + advice.rules()));
  }

  private static SignoffOptions trusted() {
    return SignoffOptions.defaults(List.of("zingaltern"));
  }

  @Test
  void highRiskAuthPathGivesTwoSigners() {
    SignoffAdvice advice =
        assess(trusted(), TRUSTED, "src/main/java/com/acme/auth/AuthService.java");
    assertThat(advice.advisoryOnly()).isTrue();
    assertThat(advice.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(advice.recommendedSigners()).isEqualTo(2);
    assertThat(advice.commitSha()).isEqualTo(HEAD);
    assertThat(rule(advice, "high-risk-path").tier()).isEqualTo(RiskTier.HIGH);
    assertThat(rule(advice, "high-risk-path").evidence())
        .containsExactly("src/main/java/com/acme/auth/AuthService.java");
  }

  @Test
  void criticalConfigGivesTwoSignersEvenWithTrustedAuthorAndGreenTests() {
    SignoffAdvice advice =
        assess(trusted(), TRUSTED, ".github/workflows/ci.yml", "db/migration/V2__add.sql");
    assertThat(advice.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(advice.recommendedSigners()).isEqualTo(2);
    assertThat(rule(advice, "critical-config-path").evidence())
        .containsExactly(".github/workflows/ci.yml", "db/migration/V2__add.sql");
  }

  @Test
  void regularBusinessLogicGivesOneSigner() {
    SignoffAdvice advice = assess(trusted(), TRUSTED, "src/main/java/com/acme/OrderService.java");
    assertThat(advice.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(advice.recommendedSigners()).isEqualTo(1);
    assertThat(rule(advice, "business-logic").evidence())
        .containsExactly("src/main/java/com/acme/OrderService.java");
  }

  @Test
  void systemPromptAndReviewPolicyAreMedium() {
    SignoffAdvice prompt = assess(trusted(), TRUSTED, "prompts/shared/tool-protocol.md");
    assertThat(prompt.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(rule(prompt, "system-prompt").tier()).isEqualTo(RiskTier.MEDIUM);

    SignoffAdvice policy = assess(trusted(), TRUSTED, "REVIEW.md");
    assertThat(policy.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(rule(policy, "review-policy-change").tier()).isEqualTo(RiskTier.MEDIUM);
    // REVIEW.md 虽是 .md，但绝不能落到低风险 0 人签
    assertThat(policy.recommendedSigners()).isEqualTo(1);
  }

  @Test
  void docsOnlyWithGreenTestsAndTrustedAuthorGivesZero() {
    SignoffAdvice advice = assess(trusted(), TRUSTED, "docs/guide.md", "README.md");
    assertThat(advice.tier()).isEqualTo(RiskTier.LOW);
    assertThat(advice.recommendedSigners()).isZero();
    assertThat(rule(advice, "docs-only").tier()).isEqualTo(RiskTier.LOW);
    assertThat(rule(advice, "low-risk-conditions-met").tier()).isEqualTo(RiskTier.LOW);
    assertThat(advice.rules()).extracting(SignoffRuleHit::id).doesNotContain("low-risk-gate-tests");
  }

  @Test
  void docsOnlyWithoutKnownTestsEscalatesToMedium() {
    SignoffAdvice advice =
        assess(trusted(), new SignoffContext("zingaltern", TestsStatus.UNKNOWN), "docs/guide.md");
    assertThat(advice.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(advice.recommendedSigners()).isEqualTo(1);
    assertThat(rule(advice, "low-risk-gate-tests").reason()).contains("unknown").contains("passed");
  }

  @Test
  void docsOnlyWithUntrustedAuthorEscalatesToMedium() {
    SignoffAdvice advice =
        assess(trusted(), new SignoffContext("mallory", TestsStatus.PASSED), "docs/guide.md");
    assertThat(advice.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(rule(advice, "low-risk-gate-author").reason()).contains("mallory");
    // 作者未知同样不满足（空 trusted-authors 也不满足）
    SignoffAdvice unknownAuthor =
        assess(SignoffOptions.defaults(), SignoffContext.unknown(), "docs/guide.md");
    assertThat(unknownAuthor.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(rule(unknownAuthor, "low-risk-gate-author").reason()).contains("未知");
  }

  @Test
  void featureFlagOnlyGivesZero() {
    String flagPatch = "@@ -0,0 +1,1 @@\n+feature.new-ui.enabled: false\n";
    SignoffAdvice advice =
        new SignoffAdvisor(trusted())
            .assess(
                request(TRUSTED),
                diff(new String[] {"src/main/resources/application.yml"}, flagPatch));
    assertThat(advice.tier()).isEqualTo(RiskTier.LOW);
    assertThat(advice.recommendedSigners()).isZero();
    assertThat(rule(advice, "feature-flag-only").tier()).isEqualTo(RiskTier.LOW);
  }

  @Test
  void configWithNonFlagChangeIsBusinessLogic() {
    String normalPatch = "@@ -1,1 +1,2 @@\n context\n+server.port: 8080\n";
    SignoffAdvice advice =
        new SignoffAdvisor(trusted())
            .assess(
                request(TRUSTED),
                diff(new String[] {"src/main/resources/application.yml"}, normalPatch));
    assertThat(advice.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(advice.rules()).extracting(SignoffRuleHit::id).contains("business-logic");
  }

  @Test
  void highRiskWinsOverMixedDocumentationAndLowRiskCandidates() {
    SignoffAdvice advice =
        assess(trusted(), TRUSTED, "docs/guide.md", "src/main/java/com/acme/auth/TokenStore.java");
    assertThat(advice.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(advice.recommendedSigners()).isEqualTo(2);
    assertThat(advice.rules()).extracting(SignoffRuleHit::id).doesNotContain("docs-only");
  }

  @Test
  void documentationUnderSecurityDirectoryRemainsDocumentation() {
    SignoffAdvice advice = assess(trusted(), TRUSTED, "docs/security/guide.md");
    assertThat(advice.tier()).isEqualTo(RiskTier.LOW);
    assertThat(advice.rules()).extracting(SignoffRuleHit::id).doesNotContain("high-risk-path");
  }

  @Test
  void disabledConfigYieldsNoAdvice() {
    SignoffAdvice advice =
        new SignoffAdvisor(SignoffOptions.disabled())
            .assess(request(TRUSTED), diff("src/main/java/com/acme/auth/AuthService.java"));
    assertThat(advice.applicable()).isFalse();
    assertThat(advice.advisoryOnly()).isFalse();
    assertThat(advice.tier()).isNull();
    assertThat(advice.recommendedSigners()).isZero();
  }

  @Test
  void emptyDiffIsLowWithNoSigners() {
    SignoffAdvice advice =
        new SignoffAdvisor(trusted())
            .assess(
                request(SignoffContext.unknown()),
                new DiffResult(BASE, HEAD, List.of(), List.of()));
    assertThat(advice.tier()).isEqualTo(RiskTier.LOW);
    assertThat(advice.recommendedSigners()).isZero();
    assertThat(rule(advice, "no-change").tier()).isEqualTo(RiskTier.LOW);
  }

  @Test
  void rulesAreOrderedHighFirstAndAlwaysExplainable() {
    SignoffAdvice advice =
        assess(
            trusted(),
            TRUSTED,
            "src/main/java/com/acme/auth/AuthService.java",
            "src/main/java/com/acme/OrderService.java",
            "docs/guide.md");
    assertThat(advice.rules()).isNotEmpty();
    assertThat(advice.rules().get(0).tier()).isEqualTo(RiskTier.HIGH);
    assertThat(advice.rules())
        .allSatisfy(
            hit -> {
              assertThat(hit.id()).isNotBlank();
              assertThat(hit.reason()).isNotBlank();
            });
  }
}
