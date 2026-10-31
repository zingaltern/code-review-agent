package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI 参数解析（05§3 语义；退出码映射在 EvalCli.execute）。 */
class EvalCliArgsTest {

  @TempDir Path tmp;

  @Test
  void runDefaultsDepthQuickAndLocatesJarHint() {
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    try {
      Files.writeString(jar, "stub");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    EvalCliArgs.RunArgs args =
        (EvalCliArgs.RunArgs)
            EvalCliArgs.parse(
                new String[] {
                  "run", "--dataset", "ds", "--cli-jar", jar.toString(), "--k", "2",
                },
                Map.of("CRA_LLM_API_KEY", "k", "CRA_LLM_BASE_URL", "http://x"));
    assertThat(args.dataset()).isEqualTo(Path.of("ds"));
    assertThat(args.set()).isEqualTo("all");
    assertThat(args.k()).isEqualTo(2);
    assertThat(args.depth()).isEqualTo("quick"); // 评测默认 quick（04§2.4）
    assertThat(args.cliJar()).isEqualTo(jar.toAbsolutePath());
    assertThat(args.timeoutSec()).isEqualTo(900);
  }

  @Test
  void missingLlmEnvIsDetectableButNotFatal() throws Exception {
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    Files.writeString(jar, "stub");
    EvalCliArgs.RunArgs args =
        (EvalCliArgs.RunArgs)
            EvalCliArgs.parse(
                new String[] {"run", "--dataset", "ds", "--cli-jar", jar.toString()}, Map.of());
    assertThat(CliProcessRunner.llmConfigured(Map.of())).isFalse();
    assertThat(CliProcessRunner.llmConfigured(Map.of("CRA_LLM_API_KEY", "k"))).isFalse();
    assertThat(
            CliProcessRunner.llmConfigured(Map.of("CRA_LLM_API_KEY", "k", "CRA_LLM_BASE_URL", "u")))
        .isTrue();
    // 缺省时 runner 会注入占位值，run 如实失败于 LLM 步骤
    Map<String, String> env = CliProcessRunner.llmEnv(Map.of());
    assertThat(env).containsEntry("CRA_LLM_API_KEY", "cra-eval-no-key");
    assertThat(args).isNotNull();
  }

  @Test
  void rejectsUnknownSubcommandAndMissingValues() {
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"bogus"}, Map.of()))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"run"}, Map.of()))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"run", "--dataset"}, Map.of()))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"run", "--dataset", "d", "--k", "0", "--cli-jar", "x"}, Map.of()))
        .isInstanceOf(CraConfigException.class);
  }

  @Test
  void annotateActions() {
    EvalCliArgs.AnnotateArgs args =
        (EvalCliArgs.AnnotateArgs)
            EvalCliArgs.parse(new String[] {"annotate", "--fixture", "cases/bad-A-1"}, Map.of());
    assertThat(args.action()).isEqualTo("prefill");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"annotate", "--fixture", "x", "--action", "relabel"}, Map.of()))
        .isInstanceOf(CraConfigException.class);
    EvalCliArgs.AnnotateArgs relabel =
        (EvalCliArgs.AnnotateArgs)
            EvalCliArgs.parse(
                new String[] {
                  "annotate",
                  "--fixture",
                  "x",
                  "--action",
                  "relabel",
                  "--to-set",
                  "bad",
                  "--reason",
                  "误标"
                },
                Map.of());
    assertThat(relabel.toSet()).isEqualTo("bad");
  }

  @Test
  void reportAndBadcaseSubcommands() {
    EvalCliArgs.ReportArgs report =
        (EvalCliArgs.ReportArgs)
            EvalCliArgs.parse(
                new String[] {
                  "report",
                  "--repo",
                  "o/r",
                  "--since",
                  "7d",
                  "--ledger-fixture",
                  "fx",
                  "--trace-dir",
                  "a,b",
                  "--previous",
                  "p1,p2"
                },
                Map.of());
    assertThat(report.repo().owner()).isEqualTo("o");
    assertThat(report.since()).isEqualTo("7d");
    assertThat(report.ledgerFixture()).isEqualTo(Path.of("fx"));
    assertThat(report.traceDirs()).containsExactly(Path.of("a"), Path.of("b"));
    assertThat(report.previous()).containsExactly(Path.of("p1"), Path.of("p2"));
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"report"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--repo");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"report", "--repo", "o/r", "--since", "7x"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--since");

    EvalCliArgs.BadcaseArgs badcase =
        (EvalCliArgs.BadcaseArgs)
            EvalCliArgs.parse(
                new String[] {
                  "badcase", "add", "runs/run_1", "f_1", "--label", "missed", "--dataset", "ds"
                },
                Map.of());
    assertThat(badcase.runRef()).isEqualTo("runs/run_1");
    assertThat(badcase.findingId()).isEqualTo("f_1");
    assertThat(badcase.label()).isEqualTo("missed");
    assertThat(badcase.dataset()).isEqualTo(Path.of("ds"));
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"badcase", "add", "r", "f", "--label", "nope", "--dataset", "ds"},
                    Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--label");
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"badcase", "add"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("badcase add");
  }

  @Test
  void runGateFlagsAreAdditiveAndBooleanFlagsConsumeNoValue() throws Exception {
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    Files.writeString(jar, "stub");
    EvalCliArgs.RunArgs args =
        (EvalCliArgs.RunArgs)
            EvalCliArgs.parse(
                new String[] {
                  "run",
                  "--dataset",
                  "ds",
                  "--cli-jar",
                  jar.toString(),
                  "--fail-on-red",
                  "--k",
                  "2",
                  "--tier",
                  "l2",
                  "--commit",
                  "abc1234",
                  "--json",
                  "out.json",
                  "--sha256-out",
                  "out.sha256",
                  "--crosschecker-enabled",
                  "--semantic-context"
                },
                Map.of());
    assertThat(args.failOnRed()).isTrue();
    assertThat(args.k()).isEqualTo(2);
    assertThat(args.tier()).isEqualTo("l2");
    assertThat(args.commit()).isEqualTo("abc1234");
    assertThat(args.sha256Out()).isEqualTo(Path.of("out.sha256"));
    assertThat(args.crosscheckerEnabled()).isTrue();
    assertThat(args.semanticContext()).isTrue();
  }

  @Test
  void semanticContextDefaultsOffWhenFlagAbsent() throws Exception {
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    Files.writeString(jar, "stub");
    EvalCliArgs.RunArgs args =
        (EvalCliArgs.RunArgs)
            EvalCliArgs.parse(
                new String[] {"run", "--dataset", "ds", "--cli-jar", jar.toString()}, Map.of());
    assertThat(args.semanticContext()).isFalse();
  }

  @Test
  void commitFallsBackToGithubShaAndTierDefaultsToAuto() throws Exception {
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    Files.writeString(jar, "stub");
    EvalCliArgs.RunArgs args =
        (EvalCliArgs.RunArgs)
            EvalCliArgs.parse(
                new String[] {"run", "--dataset", "ds", "--cli-jar", jar.toString()},
                Map.of("GITHUB_SHA", "feedface"));
    assertThat(args.tier()).isEqualTo("auto");
    assertThat(args.commit()).isEqualTo("feedface");
    assertThat(args.failOnRed()).isFalse();
    assertThat(args.sha256Out()).isNull();
  }

  @Test
  void invalidTierIsRejected() {
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"run", "--dataset", "ds", "--cli-jar", "x", "--tier", "l9"},
                    Map.of()))
        .isInstanceOf(CraConfigException.class);
  }

  @Test
  void verifyParsesHashTierAndCommit() {
    EvalCliArgs.VerifyArgs args =
        (EvalCliArgs.VerifyArgs)
            EvalCliArgs.parse(
                new String[] {
                  "verify",
                  "--report",
                  "l2.json",
                  "--sha256-file",
                  "l2.json.sha256",
                  "--tier",
                  "l2",
                  "--expect-commit",
                  "abc1234"
                },
                Map.of());
    assertThat(args.report()).isEqualTo(Path.of("l2.json"));
    assertThat(args.sha256File()).isEqualTo(Path.of("l2.json.sha256"));
    assertThat(args.tier()).isEqualTo("l2");
    assertThat(args.expectCommit()).isEqualTo("abc1234");
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"verify", "--report", "x"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--sha256");
  }

  @Test
  void signatureModeParsesRepoButRepoWithoutSignatureIsRejected() {
    // --repo 只在签名模式有意义（验签的仓库限定绑定，05§6）
    EvalCliArgs.VerifyArgs args =
        (EvalCliArgs.VerifyArgs)
            EvalCliArgs.parse(
                new String[] {
                  "verify",
                  "--report",
                  "l2.json",
                  "--signature",
                  "l2.json.sig",
                  "--repo",
                  "owner/repo",
                  "--signing-key-env",
                  "MY_KEY"
                },
                Map.of());
    assertThat(args.repo()).isEqualTo("owner/repo");
    assertThat(args.signature()).isEqualTo(Path.of("l2.json.sig"));
    assertThat(args.signingKeyEnv()).isEqualTo("MY_KEY");
    String[] missingRepo = {
      "verify", "--report", "x", "--sha256", "a".repeat(64), "--repo", "owner/repo"
    };
    Map<String, String> env = Map.of();
    assertThatThrownBy(() -> EvalCliArgs.parse(missingRepo, env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--repo");
  }

  @Test
  void sha256OutWithoutJsonIsRejected() {
    // 评审修复：--sha256-out 单独给出曾静默无效，现解析期快速失败（04§2.4 哈希绑定 JSON 报告）
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {
                      "run", "--dataset", "ds", "--cli-jar", "x", "--sha256-out", "h.sha256"
                    },
                    Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--sha256-out")
        .hasMessageContaining("--json");
  }

  @Test
  void runRejectsUnknownFlagAndUnexpectedPositional() {
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"run", "--dataset", "ds", "--cli-jar", "x", "--jsson", "o.json"},
                    Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--jsson")
        .hasMessageContaining("--json");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"run", "--dataset", "ds", "--cli-jar", "x", "stray-arg"},
                    Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("stray-arg");
  }

  @Test
  void verifyAndGateRejectUnknownFlagAndPositional() {
    String[] misspelledFlag = {
      "verify", "--report", "x", "--sha256", "a".repeat(64), "--reprot", "y"
    };
    Map<String, String> env = Map.of();
    assertThatThrownBy(() -> EvalCliArgs.parse(misspelledFlag, env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--reprot");
    String[] strayPositional = {"verify", "--report", "x", "--sha256", "a".repeat(64), "stray"};
    assertThatThrownBy(() -> EvalCliArgs.parse(strayPositional, env))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("stray");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"gate", "--report", "x", "--jso-out", "y"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--jso-out");
    assertThatThrownBy(
            () -> EvalCliArgs.parse(new String[] {"gate", "--report", "x", "stray"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("stray");
  }

  @Test
  void annotateAndReportAndBadcaseRejectUnknownFlags() {
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"annotate", "--fixture", "x", "--acton", "p"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--acton");
    // annotate 合法使用位置参数（action 缺省），不拒绝
    EvalCliArgs.AnnotateArgs positional =
        (EvalCliArgs.AnnotateArgs)
            EvalCliArgs.parse(new String[] {"annotate", "--fixture", "x", "confirm"}, Map.of());
    assertThat(positional.action()).isEqualTo("confirm");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"report", "--repo", "o/r", "--sinc", "7d"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--sinc");
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {
                      "badcase", "add", "r", "f", "--labal", "missed", "--dataset", "ds"
                    },
                    Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--labal");
    // badcase 合法使用位置参数（add <run_ref> <finding_id>），不拒绝
    assertThatThrownBy(
            () ->
                EvalCliArgs.parse(
                    new String[] {"report", "--repo", "o/r", "--since", "7d", "stray"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("stray");
  }

  @Test
  void valueStartingWithDoubleDashIsRejectedAtNonFirstPosition() {
    assertThatThrownBy(
            () -> EvalCliArgs.parse(new String[] {"run", "--k", "1", "--tier", "--l2"}, Map.of()))
        .isInstanceOf(org.cra.common.exception.CraConfigException.class)
        .hasMessageContaining("参数缺值: --tier")
        .hasMessageContaining("--l2");
  }

  @Test
  void valueStartingWithDoubleDashIsReportedAsMissingValue() {
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"run", "--tier", "--l2"}, Map.of()))
        .isInstanceOf(org.cra.common.exception.CraConfigException.class)
        .hasMessageContaining("参数缺值: --tier")
        .hasMessageContaining("--l2");
  }
}
