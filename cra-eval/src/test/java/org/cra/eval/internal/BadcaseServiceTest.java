package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T3.5 badcase 固化：定位 finding → diff/快照 → meta/草稿 → annotate 流程 → 下次回归可见。 */
class BadcaseServiceTest {

  private static final Path RUN = Path.of("src/test/resources/badcase-run/run_demo_001");
  private static final Path DIFF = Path.of("src/test/resources/badcase-run/diff.patch");
  private static final Path SNAPSHOT = Path.of("src/test/resources/badcase-run/snapshot");

  @TempDir Path tmp;

  private BadcaseService.Inputs inputs(Path dataset, String label, String findingId, String toSet) {
    return new BadcaseService.Inputs(
        dataset,
        RUN.toString(),
        RUN,
        findingId,
        label,
        toSet,
        null,
        "tester",
        "端到端测试",
        DIFF,
        SNAPSHOT,
        null,
        null);
  }

  /** S5961 拆分：结果字段与落盘产物（原 31 断言按场景拆为 3 个用例，断言总量不变）。 */
  @Test
  void missedBadcaseResultAndCaseFilesAreCreated() {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Result result =
        BadcaseService.add(inputs(dataset, "missed", "f_missed01", null), null, null);

    assertThat(result.set()).isEqualTo("bad");
    assertThat(result.findingSource()).isEqualTo("brief");
    assertThat(result.verifierVerdict()).isEqualTo("REFUTED");
    assertThat(result.finding().file()).isEqualTo("src/main/java/Foo.java");
    assertThat(result.finding().line()).isEqualTo(42);
    assertThat(result.changedFiles()).isEqualTo(1);

    Path caseDir = result.caseDir();
    assertThat(caseDir).exists();
    assertThat(caseDir.resolve("diff.patch")).exists();
    assertThat(caseDir.resolve("repo_snapshot.tgz")).exists();
    assertThat(caseDir.resolve("badcase.json")).exists();
  }

  @Test
  void missedBadcaseMetaAndDraftExpectedArePersisted() {
    Path dataset = tmp.resolve("dataset");
    Path caseDir =
        BadcaseService.add(inputs(dataset, "missed", "f_missed01", null), null, null).caseDir();

    FixtureMeta meta = Json.readMeta(caseDir.resolve("meta.json"));
    assertThat(meta.set()).isEqualTo("bad");
    assertThat(meta.source()).isEqualTo("production");
    assertThat(meta.annotation().status()).isEqualTo("draft");
    assertThat(meta.headSha()).isEqualTo("headsha77");
    assertThat(meta.snapshot().sha256()).isNotBlank();
    assertThat(meta.note()).contains("f_missed01").contains("snapshot_files=");

    ExpectedFindings draft = Json.readExpected(caseDir.resolve("expected_findings.draft.json"));
    assertThat(draft.expected()).hasSize(1);
    assertThat(draft.expected().get(0).file()).isEqualTo("src/main/java/Foo.java");
    assertThat(draft.expected().get(0).line()).isEqualTo(42);
    assertThat(draft.expected().get(0).category()).isEqualTo("bug");
    assertThat(draft.expected().get(0).keywords()).isNotEmpty();
  }

  @Test
  void missedBadcaseIsListedAndLoadsAfterConfirm() {
    Path dataset = tmp.resolve("dataset");
    Path caseDir =
        BadcaseService.add(inputs(dataset, "missed", "f_missed01", null), null, null).caseDir();

    // 数据集枚举（下次回归的入口）已包含该用例
    assertThat(DatasetLoader.listCases(dataset, "bad")).contains(caseDir);

    // draft 用例不可跑：annotate confirm 后才进入正式断言
    AnnotateService.confirm(caseDir, "tester");
    FixtureMeta confirmed = Json.readMeta(caseDir.resolve("meta.json"));
    assertThat(confirmed.annotation().status()).isEqualTo("annotated");
    DatasetLoader.LoadedCase loaded = DatasetLoader.load(caseDir, tmp.resolve("cache"));
    assertThat(loaded.expected().expected()).hasSize(1);
    assertThat(loaded.snapshotRoot().resolve("src/main/java/Foo.java")).exists();
  }

  @Test
  void falsePositiveGoesToCleanWithEmptyExpected() {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Result result =
        BadcaseService.add(inputs(dataset, "false-positive", "f_missed01", null), null, null);
    assertThat(result.set()).isEqualTo("clean");
    ExpectedFindings draft =
        Json.readExpected(result.caseDir().resolve("expected_findings.draft.json"));
    assertThat(draft.expected()).isEmpty();
  }

  @Test
  void relabeledRequiresTargetSetAndHonoursIt() {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Result result =
        BadcaseService.add(inputs(dataset, "relabeled", "f_missed01", "clean"), null, null);
    assertThat(result.set()).isEqualTo("clean");
    BadcaseService.Inputs invalidTarget =
        inputs(tmp.resolve("d2"), "relabeled", "f_missed01", "weird");
    assertThatThrownBy(() -> BadcaseService.add(invalidTarget, null, null))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("--to-set");
  }

  @Test
  void missingFindingFailsWithoutInventingContent() {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Inputs unknownFinding = inputs(dataset, "missed", "f_nope", null);
    assertThatThrownBy(() -> BadcaseService.add(unknownFinding, null, null))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("未定位到 finding_id");
    assertThat(dataset.resolve("bad")).doesNotExist();
  }

  @Test
  void draftCaseIsRefusedBeforeDataLoading() throws Exception {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Result result =
        BadcaseService.add(inputs(dataset, "missed", "f_missed01", null), null, null);
    Path jar = tmp.resolve("cra-runner-cli-0.1.0.jar");
    Files.writeString(jar, "stub");
    EvalCliArgs.RunArgs runArgs =
        new EvalCliArgs.RunArgs(
            dataset,
            "bad",
            1,
            "quick",
            jar,
            tmp.resolve("work"),
            null,
            60,
            java.util.List.of(result.caseId()),
            null,
            null,
            false,
            null,
            false,
            false);
    assertThatThrownBy(() -> RunCommand.execute(runArgs, Map.of()))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("标注仍为 draft");
  }

  @Test
  void existingCaseIsNotOverwritten() throws Exception {
    Path dataset = tmp.resolve("dataset");
    BadcaseService.Result first =
        BadcaseService.add(inputs(dataset, "missed", "f_missed01", null), null, null);
    Files.writeString(first.caseDir().resolve("manual-note.txt"), "keep me");
    BadcaseService.Inputs duplicate = inputs(dataset, "missed", "f_missed01", null);
    assertThatThrownBy(() -> BadcaseService.add(duplicate, null, null))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("已存在");
    assertThat(first.caseDir().resolve("manual-note.txt")).hasContent("keep me");
  }
}
