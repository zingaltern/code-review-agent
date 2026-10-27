package org.cra.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 事件史数据模型与路径匹配（ADR-0012）：规范化、幂等键语义、路径边界。 */
class EventModelTest {

  private static final Instant AT = Instant.parse("2026-08-14T03:00:00Z");

  @Test
  void productEventNormalizesRepoCommitsAndPaths() {
    ProductEvent event =
        new ProductEvent(
            "INC-42",
            " Owner/Repo ",
            " billing ",
            null,
            AT,
            " Incident ",
            " 会话超时事故 ",
            " https://tracker/INC-42 ",
            List.of("ABC1234", "abc1234", "deadbeef"),
            List.of("./src/billing/**", "src/billing/", "src\\billing\\Invoice.java"),
            "{\"k\":1}",
            AT.plusSeconds(60));

    assertThat(event.repo()).isEqualTo("owner/repo");
    assertThat(event.type()).isEqualTo("incident");
    assertThat(event.summary()).isEqualTo("会话超时事故");
    assertThat(event.service()).isEqualTo("billing");
    assertThat(event.tenant()).isNull();
    assertThat(event.commitRefs()).containsExactly("abc1234", "deadbeef");
    assertThat(event.paths()).containsExactly("src/billing", "src/billing/Invoice.java");
    assertThat(event.incidentLike()).isTrue();
    assertThat(event.ticketLike()).isFalse();
  }

  @Test
  void postmortemCountsAsIncidentLikeAndTicketIsSeparate() {
    assertThat(event("INC-1", "postmortem").incidentLike()).isTrue();
    assertThat(event("T-1", "ticket").ticketLike()).isTrue();
    assertThat(event("C-1", "change").incidentLike()).isFalse();
  }

  @Test
  void rejectsInvalidEventIdCommitRefAndMissingFields() {
    assertThatThrownBy(() -> event("bad id", "incident"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event_id");
    assertThatThrownBy(
            () ->
                new ProductEvent(
                    "INC-1",
                    "o/r",
                    null,
                    null,
                    AT,
                    "incident",
                    "s",
                    null,
                    List.of("not-hex"),
                    List.of(),
                    "",
                    AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commit_refs");
    assertThatThrownBy(
            () ->
                new ProductEvent(
                    "INC-1",
                    "o/r",
                    null,
                    null,
                    null,
                    "incident",
                    "s",
                    null,
                    List.of(),
                    List.of(),
                    "",
                    AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("occurred_at");
  }

  @Test
  void pathMatcherUsesSegmentBoundaries() {
    assertThat(EventPathMatcher.normalize("src/billing/**")).isEqualTo("src/billing");
    assertThat(EventPathMatcher.normalize("./src\\billing\\")).isEqualTo("src/billing");
    assertThat(EventPathMatcher.normalize("src/billing/*.java")).isEqualTo("src/billing/*.java");

    assertThat(EventPathMatcher.matches("src/billing", "src/billing/Invoice.java")).isTrue();
    assertThat(EventPathMatcher.matches("src/billing/", "src/billing/Invoice.java")).isTrue();
    assertThat(EventPathMatcher.matches("src/billing/Invoice.java", "src/billing/Invoice.java"))
        .isTrue();
    assertThat(EventPathMatcher.matches("src/billing", "src/billing2/Invoice.java")).isFalse();
    assertThat(EventPathMatcher.matches("src/billing/Invoice.java", "src/billing/Other.java"))
        .isFalse();
    assertThat(EventPathMatcher.matches("src/billing/*.java", "src/billing/Invoice.java")).isTrue();
    assertThat(EventPathMatcher.matches("**", "anything/at/all.java")).isTrue();
  }

  @Test
  void eventQueryClampsLimitAndRejectsInvertedWindow() {
    EventQuery query = new EventQuery(" Owner/Repo ", List.of(), List.of(), List.of(), AT, null, 0);
    assertThat(query.repo()).isEqualTo("owner/repo");
    assertThat(query.limit()).isEqualTo(EventQuery.DEFAULT_LIMIT);

    EventQuery capped = new EventQuery("o/r", List.of(), List.of(), List.of(), AT, null, 10_000);
    assertThat(capped.limit()).isEqualTo(200);

    assertThatThrownBy(
            () -> new EventQuery("o/r", List.of(), List.of(), List.of(), AT.plusSeconds(1), AT, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("since");
  }

  private static ProductEvent event(String id, String type) {
    return new ProductEvent(
        id, "o/r", null, null, AT, type, "summary", null, List.of(), List.of(), "", AT);
  }
}
