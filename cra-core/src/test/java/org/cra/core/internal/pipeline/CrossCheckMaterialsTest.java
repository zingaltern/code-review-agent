package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.git.CommitHistoryEntry;
import org.cra.common.git.DiffFile;
import org.junit.jupiter.api.Test;

/** CrossChecker 材料组装（03§3.1/03§6 预算行）：diff 精简视图、变更史摘要、产品段与裁剪声明。 */
class CrossCheckMaterialsTest {

  private static final DiffFile FILE =
      new DiffFile(
          "src/Foo.java",
          null,
          "modified",
          "@@ -10,3 +10,5 @@\n context-line-1\n+added one\n+added two\n context-line-2",
          List.of());

  @Test
  void diffDigestKeepsOnlyHunkHeadersAndChangedLines() {
    String digest = CrossCheckMaterials.diffDigest(List.of(FILE));
    assertThat(digest)
        .contains("@@ -10,3 +10,5 @@")
        .contains("+added one")
        .doesNotContain("context-line-1")
        .doesNotContain(" context-line-2");
  }

  @Test
  void historyTextRendersShaTimeAndFirstMessageLine() {
    List<CommitHistoryEntry> history =
        List.of(
            new CommitHistoryEntry(
                "abc1234567890",
                "feat: 企业长会话定制 8h\n\n详情不应注入",
                Instant.parse("2026-09-08T10:00:00Z")));
    String text = CrossCheckMaterials.historyText(history, 8);
    assertThat(text)
        .contains("近 8 周")
        .contains("abc1234")
        .contains("2026-09-08T10:00:00Z")
        .contains("feat: 企业长会话定制 8h")
        .doesNotContain("详情不应注入");
  }

  @Test
  void trustedSectionsFilterByMode() {
    ReviewPolicy policy =
        new ReviewPolicy(
            true,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("后台任务默认在常规会话内跑完"),
            List.of("2026-08-14 会话超时事故"),
            List.of("超时类参数不得超过上游超时 80%"));

    String assumptionOnly = CrossCheckMaterials.trustedSections(policy, true, false);
    assertThat(assumptionOnly)
        .contains("Product context")
        .contains("Known incidents")
        .doesNotContain("Sniff tests");

    String sniffOnly = CrossCheckMaterials.trustedSections(policy, false, true);
    assertThat(sniffOnly).contains("Sniff tests").doesNotContain("Known incidents");
  }

  @Test
  void buildCapsEachSectionAndDeclaresTruncation() {
    // 超大输入：每段都应被裁到 03§6 预算内，且裁剪事实进 notes（覆盖声明可见）
    StringBuilder hugeHistory = new StringBuilder();
    for (int i = 0; i < 3_000; i++) {
      hugeHistory
          .append("- 2026-09-08T10:00:00Z abc1234 feat: 企业长会话定制并携带大量说明文本 ")
          .append(i)
          .append('\n');
    }
    StringBuilder hugeProduct = new StringBuilder();
    for (int i = 0; i < 1_500; i++) {
      hugeProduct.append("- 产品背景条目 ").append(i).append('\n');
    }
    ReviewPolicy policy =
        new ReviewPolicy(
            true,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(hugeProduct.toString()),
            List.of(),
            List.of());
    StringBuilder hugePatch = new StringBuilder("@@ -1 +1,5000 @@\n");
    for (int i = 0; i < 5_000; i++) {
      hugePatch.append('+').append("代码行 ").append(i).append('\n');
    }
    DiffFile hugeFile =
        new DiffFile("src/Huge.java", null, "modified", hugePatch.toString(), List.of());

    CrossCheckMaterials.Materials materials =
        CrossCheckMaterials.build(List.of(hugeFile), List.of(), 8, policy, true, false);

    assertThat(TokenMath.estimateTokens(materials.diffDigest()))
        .isLessThanOrEqualTo(CrossCheckMaterials.DIFF_TOKEN_CAP + 10);
    assertThat(TokenMath.estimateTokens(materials.trustedSections()))
        .isLessThanOrEqualTo(CrossCheckMaterials.CONTEXT_TOKEN_CAP + 10);
    assertThat(materials.notes()).anyMatch(n -> n.contains("diff 精简视图超预算截断"));
    assertThat(materials.notes()).anyMatch(n -> n.contains("产品段超预算截断"));
  }
}
