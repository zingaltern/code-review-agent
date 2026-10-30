package org.cra.eval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FixtureMeta 对抗子类校验（05§1.1 / 06§1.2 第 4 类）与 meta.json 外部输入边界。 */
class FixtureMetaTest {

  @TempDir Path tmp;

  @Test
  void contextSubtypeAccepted() {
    assertThat(meta("context").adversarialSubtype()).isEqualTo("context");
  }

  @Test
  void injectionHallucinationRefusalStillAccepted() {
    assertThat(meta("injection").adversarialSubtype()).isEqualTo("injection");
    assertThat(meta("hallucination").adversarialSubtype()).isEqualTo("hallucination");
    assertThat(meta("refusal").adversarialSubtype()).isEqualTo("refusal");
  }

  @Test
  void unknownSubtypeRejected() {
    assertThatThrownBy(() -> meta("bogus"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("adversarial_subtype");
  }

  @Test
  void nullSubtypeAllowedForNonAdversarial() {
    assertThat(meta(null).adversarialSubtype()).isNull();
  }

  @Test
  void rejectsCaseIdPathTraversal() throws Exception {
    Path meta = writeMeta("../evil");
    assertThatThrownBy(() -> Json.readMeta(meta))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("case_id");
  }

  @Test
  void rejectsSnapshotFileTraversal() throws Exception {
    Path meta = writeMeta("adv-ok");
    String json = Files.readString(meta).replace("repo_snapshot.tgz", "../../../etc/passwd");
    Files.writeString(meta, json);
    assertThatThrownBy(() -> Json.readMeta(meta))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("snapshot.file");
  }

  private static FixtureMeta meta(String subtype) {
    return new FixtureMeta(
        1,
        "case",
        subtype == null ? "bad" : "adversarial",
        subtype,
        "synthetic",
        null,
        null,
        null,
        "base",
        "head",
        new FixtureMeta.Snapshot("repo_snapshot.tgz", "abc", "."),
        new FixtureMeta.ReviewRequestSpec(1, "o", "r"),
        java.util.List.of(),
        new FixtureMeta.Annotation("annotated", "t", "2026-10-26"),
        new FixtureMeta.Recheck("pending", null, null),
        "");
  }

  private Path writeMeta(String caseId) throws Exception {
    String json =
        """
        {"format_version":1,"case_id":"%s","set":"bad","adversarial_subtype":null,
         "source":"synthetic","project":null,"bug_id":null,"report_id":null,
         "base_sha":"base","head_sha":"head",
         "snapshot":{"file":"repo_snapshot.tgz","sha256":null,"crop_root":""},
         "review_request":{"pr_number":1,"repo_owner":"o","repo_name":"r"},
         "filtered_test_files":[],"annotation":null,"recheck":null,"note":null}
        """
            .formatted(caseId);
    Path meta = tmp.resolve("meta.json");
    Files.writeString(meta, json);
    return meta;
  }
}
