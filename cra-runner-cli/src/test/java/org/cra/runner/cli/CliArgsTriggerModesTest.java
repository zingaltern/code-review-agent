package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.BlameResult;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.common.run.ReviewMode;
import org.cra.git.api.LocalRepoContext;
import org.junit.jupiter.api.Test;

/** M4c/M4d CLI 契约（05§3）：push 参数、sweep 扫描范围、push 基线解析。 */
class CliArgsTriggerModesTest {

  private static final Map<String, String> ENV =
      Map.of(
          "GITHUB_TOKEN", "gh-env-token",
          "CRA_LLM_API_KEY", "sk-env",
          "CRA_LLM_BASE_URL", "https://llm.example/v1",
          "CRA_LLM_MODEL", "glm-x");

  @Test
  void pushModeNeedsNoPrAndDefaultsLookback() {
    CraConfig config =
        CliArgs.parse(new String[] {"review", "--push", "--repo", "o/r", "--head", "def"}, ENV);
    assertThat(config.reviewMode()).isEqualTo(ReviewMode.COMMIT);
    assertThat(config.prNumber()).isZero();
    assertThat(config.baseSha()).isNull(); // 执行期沿首父链查账本解析
    assertThat(config.pushLookback()).isEqualTo(CraConfig.DEFAULT_PUSH_LOOKBACK);
  }

  @Test
  void pushModeAcceptsExplicitBaseAndLookback() {
    CraConfig config =
        CliArgs.parse(
            new String[] {
              "--push", "--repo", "o/r", "--head", "def", "--baseline", "abc", "--lookback", "5"
            },
            ENV);
    assertThat(config.baseSha()).isEqualTo("abc");
    assertThat(config.pushLookback()).isEqualTo(5);
  }

  @Test
  void pushModeRejectsPrAndLocal() {
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {"--push", "--repo", "o/r", "--head", "def", "--pr", "7"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("无 PR 编号");
    assertThatThrownBy(
            () ->
                CliArgs.parse(
                    new String[] {"--push", "--local", "--repo", "o/r", "--head", "def"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("互斥");
  }

  @Test
  void sweepParsesScopeAndUsesIssueMode() {
    SweepArgs.Parsed parsed =
        SweepArgs.parse(
            new String[] {"--repo", "o/r", "--since", "7d", "--max-prs", "3", "--depth", "quick"},
            ENV);
    assertThat(parsed.since()).isEqualTo("7d");
    assertThat(parsed.maxPrs()).isEqualTo(3);
    assertThat(parsed.config().reviewMode()).isEqualTo(ReviewMode.ISSUE);
    assertThat(parsed.config().depth()).isEqualTo("quick");
  }

  @Test
  void sweepRejectsInvalidScope() {
    assertThatThrownBy(() -> SweepArgs.parse(new String[] {"--since", "30q"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--repo");
    assertThatThrownBy(() -> SweepArgs.parse(new String[] {"--repo", "o/r", "--since", "30q"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--since");
    assertThatThrownBy(() -> SweepArgs.parse(new String[] {"--repo", "o/r", "--max-prs", "0"}, ENV))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--max-prs");
  }

  @Test
  void sweepScopeParsesHoursDaysWeeks() {
    assertThat(SweepScope.parseSince("12h").toHours()).isEqualTo(12);
    assertThat(SweepScope.parseSince("2d").toDays()).isEqualTo(2);
    assertThat(SweepScope.parseSince("2w").toDays()).isEqualTo(14);
    assertThatThrownBy(() -> SweepScope.parseSince("0d")).isInstanceOf(CraConfigException.class);
  }

  @Test
  void pushBaseResolverPicksLatestCompletedCommit() {
    CraConfig config = pushConfig(null);
    CommentSource source = sourceWithCompletions(List.of("c2"));
    String base = PushBaseResolver.resolve(config, source, localChain("head", "c2", "c1", "root"));
    assertThat(base).isEqualTo("c2");
  }

  @Test
  void pushBaseResolverFallsBackToRepoInitialCommit() {
    CraConfig config = pushConfig(null);
    String base =
        PushBaseResolver.resolve(
            config, sourceWithCompletions(List.of()), localChain("head", "c2", "root"));
    assertThat(base).isEqualTo("root");
  }

  @Test
  void pushBaseResolverRequiresLocalRepoWhenBaseMissing() {
    CraConfig config = pushConfig(null);
    CommentSource source = sourceWithCompletions(List.of());
    LocalRepoContext chain = localChain();
    assertThatThrownBy(() -> PushBaseResolver.resolve(config, source, chain))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("无法解析基线");
  }

  @Test
  void pushBaseResolverHonoursExplicitBase() {
    CraConfig config = pushConfig("explicit");
    assertThat(
            PushBaseResolver.resolve(
                config, sourceWithCompletions(List.of("c2")), localChain("head", "c2")))
        .isEqualTo("explicit");
  }

  private static CraConfig pushConfig(String base) {
    String[] args =
        base == null
            ? new String[] {"--push", "--repo", "o/r", "--head", "head"}
            : new String[] {"--push", "--repo", "o/r", "--head", "head", "--base", base};
    return CliArgs.parse(args, ENV);
  }

  private static CommentSource sourceWithCompletions(List<String> completedShas) {
    return new CommentSource() {
      @Override
      public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
        throw new AssertionError("push 基线解析只查 commit 载体");
      }

      @Override
      public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
        return new DispositionSignal[0];
      }

      @Override
      public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
        if (!completedShas.contains(commitSha)) {
          return new CommentLedger(List.of(), List.of(), List.of(), 0);
        }
        return new CommentLedger(
            List.of(),
            List.of(),
            List.of(new LedgerRunMarker("run_" + commitSha, commitSha, "DONE", 0, 0, 1L)),
            0);
      }
    };
  }

  private static LocalRepoContext localChain(String... shas) {
    return new LocalRepoContext() {
      @Override
      public BlameResult blame(String path, int line, String sha) {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Optional<String> localRepoRoot() {
        return java.util.Optional.of(".");
      }

      @Override
      public List<String> firstParentChain(String headSha, int limit) {
        return List.of(shas).subList(0, Math.min(limit, shas.length));
      }
    };
  }
}
