package org.cra.common.finding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 矛盾来源引用格式（03§4.2 v1.3.1 权威定义，M4f 落定）：解析规则与 M2 裸 hex 兼容。 */
class ContextCitationTest {

  @Test
  void parsesStructuredCommitRefsWithPath() {
    String evidence =
        """
        L10: return sessionAge < 30min;
        矛盾来源：
        - commit abc1234 path src/main/java/demo/SessionKeepAlive.java — 企业长会话定制 8h
        - commit DEF5678 — 未注明路径，按 finding.file 兜底
        """;

    List<ContextCitation.CommitRef> refs =
        ContextCitation.commitRefs(evidence, "src/main/java/demo/Foo.java");

    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).sha()).isEqualTo("abc1234");
    assertThat(refs.get(0).effectivePath("fallback"))
        .isEqualTo("src/main/java/demo/SessionKeepAlive.java");
    assertThat(refs.get(1).sha()).isEqualTo("def5678");
    assertThat(refs.get(1).effectivePath("src/main/java/demo/Foo.java"))
        .isEqualTo("src/main/java/demo/Foo.java");
  }

  @Test
  void fallsBackToBareHexWhenNoStructuredRef() {
    // M2 兼容语义：无结构化引用时裸 hex 仍可解析（path 以 finding.file 兜底）
    List<ContextCitation.CommitRef> refs =
        ContextCitation.commitRefs("对照 abc1234 的改动，另见 deadbeef", "src/Foo.java");
    assertThat(refs)
        .extracting(ContextCitation.CommitRef::sha)
        .containsExactly("abc1234", "deadbeef");
    assertThat(refs).allSatisfy(ref -> assertThat(ref.path()).isEqualTo("src/Foo.java"));
  }

  @Test
  void parsesReviewMdSectionAnchors() {
    String evidence =
        """
        矛盾来源：
        - REVIEW.md Known incidents: 2026-08-14 会话超时事故：隐含假设被六周前企业定制破坏
        - REVIEW.md Sniff tests: 超时/重试类参数不得超过上游服务超时的 80%
        - REVIEW.md Product context
        """;

    List<ContextCitation.ReviewMdRef> refs = ContextCitation.reviewMdRefs(evidence);

    assertThat(refs).hasSize(3);
    assertThat(refs.get(0).section()).isEqualTo(ContextCitation.KNOWN_INCIDENTS);
    assertThat(refs.get(0).entry()).contains("2026-08-14 会话超时事故");
    assertThat(refs.get(1).section()).isEqualTo(ContextCitation.SNIFF_TESTS);
    assertThat(refs.get(2).section()).isEqualTo(ContextCitation.PRODUCT_CONTEXT);
    assertThat(refs.get(2).entry()).isEmpty();
  }

  @Test
  void parsesEventRefsInsideCitationBlockOnly() {
    String evidence =
        """
        L10: 超时假设与产品事件矛盾
        正文里提到 event loop 不算引用。
        矛盾来源：
        - event INC-42 — 2026-08-14 会话超时事故
        - event: TICKET-7 说明
        - event INC-42 — 重复引用去重
        """;

    List<ContextCitation.EventRef> refs = ContextCitation.eventRefs(evidence);

    assertThat(refs)
        .extracting(ContextCitation.EventRef::eventId)
        .containsExactly("INC-42", "TICKET-7");
  }

  @Test
  void eventRefsEmptyWithoutCitation() {
    assertThat(ContextCitation.eventRefs("普通证据 L3: event loop 里没有异常")).isEmpty();
    assertThat(ContextCitation.eventRefs(null)).isEmpty();
  }

  @Test
  void noCitationYieldsEmptyLists() {
    assertThat(ContextCitation.commitRefs("普通证据 L3: x", "src/Foo.java")).isEmpty();
    assertThat(ContextCitation.reviewMdRefs("普通证据")).isEmpty();
  }
}
