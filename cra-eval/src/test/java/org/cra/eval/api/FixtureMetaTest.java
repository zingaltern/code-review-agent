package org.cra.eval.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** meta.json 外部输入边界：case_id/snapshot.file 必须是安全单段名称。 */
class FixtureMetaTest {

  @TempDir Path tmp;

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
