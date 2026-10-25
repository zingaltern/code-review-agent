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
    assertThat(env.get("CRA_LLM_API_KEY")).isEqualTo("cra-eval-no-key");
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
  void reportSubcommandIsReservedForT34() {
    assertThatThrownBy(() -> EvalCliArgs.parse(new String[] {"report", "--repo", "o/r"}, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("T3.4");
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
                  "--sha256-out",
                  "out.sha256",
                  "--crosschecker-enabled"
                },
                Map.of());
    assertThat(args.failOnRed()).isTrue();
    assertThat(args.k()).isEqualTo(2);
    assertThat(args.tier()).isEqualTo("l2");
    assertThat(args.commit()).isEqualTo("abc1234");
    assertThat(args.sha256Out()).isEqualTo(Path.of("out.sha256"));
    assertThat(args.crosscheckerEnabled()).isTrue();
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
}
