package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 报告哈希单测（T3.3 L2 门禁：本地跑 + CI 校验哈希，04§2.4 荣誉制）。 */
class ReportHasherTest {

  @TempDir Path tmp;

  @Test
  void sha256IsDeterministicAndTamperEvident() throws Exception {
    Path report = tmp.resolve("report.json");
    Files.writeString(report, "{\"gate\":{\"passed\":true}}\n");
    String hex = ReportHasher.sha256(report);
    assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(ReportHasher.sha256(report)).isEqualTo(hex);
    assertThat(ReportHasher.matches(report, hex)).isTrue();
    assertThat(ReportHasher.matches(report, hex.toUpperCase())).isTrue();

    Files.writeString(report, "{\"gate\":{\"passed\":false}}\n");
    assertThat(ReportHasher.matches(report, hex)).isFalse();
  }

  @Test
  void hashFileRoundTripSupportsSha256SumFormat() throws Exception {
    Path report = tmp.resolve("report.json");
    Files.writeString(report, "report-body");
    Path hashFile = tmp.resolve("out/report.sha256");
    String hex = ReportHasher.writeSha256File(report, hashFile);
    assertThat(Files.readString(hashFile).strip()).isEqualTo(hex);
    assertThat(ReportHasher.readSha256File(hashFile)).isEqualTo(hex);

    Path sumStyle = tmp.resolve("report.sha256.sum");
    Files.writeString(sumStyle, hex + "  report.json\n");
    assertThat(ReportHasher.readSha256File(sumStyle)).isEqualTo(hex);
  }

  @Test
  void malformedHashIsRejected() throws Exception {
    Path report = tmp.resolve("report.json");
    Files.writeString(report, "x");
    assertThatThrownBy(() -> ReportHasher.matches(report, "not-a-hash"))
        .isInstanceOf(CraConfigException.class);
    Path badFile = tmp.resolve("bad.sha256");
    Files.writeString(badFile, "zzz\n");
    assertThatThrownBy(() -> ReportHasher.readSha256File(badFile))
        .isInstanceOf(CraConfigException.class);
  }
}
