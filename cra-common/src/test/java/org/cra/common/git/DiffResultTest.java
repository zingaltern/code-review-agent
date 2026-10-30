package org.cra.common.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** diff 行数口径（02§4.4：大 diff 判据 = +/- 内容行合计，不含 hunk 头与上下文行）。 */
class DiffResultTest {

  @Test
  void countsOnlyAddedAndRemovedContentLines() {
    String patch =
        "@@ -1,3 +1,4 @@\n context one\n+added\n-removed\n+++not-a-header\n---not-a-header\n";
    DiffFile file = new DiffFile("A.java", null, "modified", patch, List.of());
    DiffResult diff = new DiffResult("base", "head", List.of(file), List.of());
    assertThat(diff.totalPatchLines()).isEqualTo(2);
  }

  @Test
  void nullPatchCountsZero() {
    DiffFile file = new DiffFile("A.java", null, "modified", null, List.of());
    assertThat(new DiffResult("base", "head", List.of(file), List.of()).totalPatchLines()).isZero();
  }
}
