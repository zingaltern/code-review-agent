package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 被测 CLI 进程参数组装（05§3 契约）：开关关闭时逐字节不变，开启时按既有顺序追加。 */
class CliProcessRunnerTest {

  private static final Path JAR = Path.of("/opt/cra/cra-runner-cli-0.1.0.jar");
  private static final Path RUN_ROOT = Path.of("/tmp/cra-eval-work/cases/bad-x/run-0");
  private static final Path SNAPSHOT = Path.of("/tmp/cra-eval-work/cache/snapshots/bad-x");

  private static List<String> args(boolean crosschecker, boolean semanticContext) {
    return CliProcessRunner.reviewArgs(
        JAR,
        "cra-eval-fixture",
        "bad-x",
        42,
        "base000",
        "head000",
        "standard",
        "http://127.0.0.1:9",
        RUN_ROOT,
        SNAPSHOT,
        crosschecker,
        semanticContext);
  }

  /** 两个开关都关闭时的完整参数（逐字节基线：新增开关不得改变既有形态）。 */
  private static List<String> flagsOffBaseline() {
    List<String> expected = new ArrayList<>();
    expected.add(JAR.toString());
    expected.add("review");
    expected.add("--repo");
    expected.add("cra-eval-fixture/bad-x");
    expected.add("--pr");
    expected.add("42");
    expected.add("--base");
    expected.add("base000");
    expected.add("--head");
    expected.add("head000");
    expected.add("--depth");
    expected.add("standard");
    expected.add("--token");
    expected.add("cra-eval-stub-token");
    expected.add("--github-api");
    expected.add("http://127.0.0.1:9");
    expected.add("--run-root");
    expected.add(RUN_ROOT.toString());
    expected.add("--workspace");
    expected.add(SNAPSHOT.toString());
    return expected;
  }

  @Test
  void flagsOffKeepsArgumentListByteIdentical() {
    assertThat(args(false, false)).isEqualTo(flagsOffBaseline());
  }

  @Test
  void crosscheckerFlagAppendsToBaseline() {
    List<String> expected = flagsOffBaseline();
    expected.add("--crosschecker");
    assertThat(args(true, false)).isEqualTo(expected);
  }

  @Test
  void semanticContextFlagAppendsAfterCrosschecker() {
    List<String> expected = flagsOffBaseline();
    expected.add("--crosschecker");
    expected.add("--semantic-context");
    assertThat(args(true, true)).isEqualTo(expected);

    List<String> withoutCrosschecker = flagsOffBaseline();
    withoutCrosschecker.add("--semantic-context");
    assertThat(args(false, true)).isEqualTo(withoutCrosschecker);
  }
}
