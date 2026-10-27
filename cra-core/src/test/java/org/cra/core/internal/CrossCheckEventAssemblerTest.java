package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.event.EventPathMatcher;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
import org.cra.core.internal.pipeline.CrossCheckEvents;
import org.junit.jupiter.api.Test;

/** 事件史装配（ADR-0012）：三类信号（路径/commit/关键词）分别检索后并集，不按 AND 漏召回。 */
class CrossCheckEventAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

  @Test
  void unionsPathCommitAndKeywordHitsInsteadOfRequiringAllSignals() {
    ProductEvent pathOnly =
        event("T-PATH", "src/billing/Invoice.java", List.of(), "报表口径不一致", NOW.minusSeconds(86400));
    ProductEvent commitOnly =
        event("T-COMMIT", null, List.of("abc1234deadbeef"), "会话续期改造", NOW.minusSeconds(2 * 86400));
    ProductEvent keywordOnly =
        event(
            "T-KEYWORD",
            null,
            List.of(),
            "invoice total mismatch after refactor",
            NOW.minusSeconds(3 * 86400));

    CrossCheckEvents events =
        CrossCheckEventAssembler.assemble(
            new StrictQuery(List.of(pathOnly, commitOnly, keywordOnly)),
            "owner/repo",
            Set.of("src/billing/Invoice.java"),
            Set.of("abc1234deadbeef0000"),
            List.of(),
            8,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(events.available()).isTrue();
    assertThat(events.entries())
        .extracting(ProductEvent::eventId)
        .containsExactly("T-PATH", "T-COMMIT", "T-KEYWORD");
    assertThat(events.notes()).anyMatch(n -> n.contains("并集"));
  }

  @Test
  void noQueryPortIsDeclaredNotSilentlyEmpty() {
    CrossCheckEvents events =
        CrossCheckEventAssembler.assemble(
            null,
            "owner/repo",
            Set.of("src/Foo.java"),
            Set.of("abc1234"),
            List.of(),
            8,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(events.available()).isFalse();
    assertThat(events.entries()).isEmpty();
    assertThat(events.notes()).anyMatch(n -> n.contains("未装配事件查询端口"));
  }

  @Test
  void searchFailureIsDeclaredNotSilentlyEmpty() {
    EventHistoryQuery failing =
        new EventHistoryQuery() {
          @Override
          public List<ProductEvent> search(EventQuery query) {
            throw new IllegalStateException("PG 不可达");
          }

          @Override
          public Optional<ProductEvent> find(String repo, String eventId) {
            return Optional.empty();
          }
        };

    CrossCheckEvents events =
        CrossCheckEventAssembler.assemble(
            failing,
            "owner/repo",
            Set.of("src/Foo.java"),
            Set.of("abc1234"),
            List.of(),
            8,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(events.available()).isFalse();
    assertThat(events.notes()).anyMatch(n -> n.contains("检索失败"));
  }

  private static ProductEvent event(
      String id, String path, List<String> commits, String summary, Instant occurredAt) {
    return new ProductEvent(
        id,
        "owner/repo",
        null,
        null,
        occurredAt,
        "incident",
        summary,
        null,
        commits,
        path == null ? List.of() : List.of(path),
        "{}",
        NOW);
  }

  /** 严格按单信号过滤（模拟 PG 端口；AND 语义下不会把三类事件混在一起）。 */
  private static final class StrictQuery implements EventHistoryQuery {

    private final List<ProductEvent> events;

    private StrictQuery(List<ProductEvent> events) {
      this.events = events;
    }

    @Override
    public List<ProductEvent> search(EventQuery query) {
      return events.stream()
          .filter(e -> query.repo() == null || e.repo().equals(query.repo()))
          .filter(e -> query.since() == null || !e.occurredAt().isBefore(query.since()))
          .filter(e -> query.until() == null || !e.occurredAt().isAfter(query.until()))
          .filter(
              e ->
                  query.paths().isEmpty()
                      || query.paths().stream()
                          .anyMatch(
                              p ->
                                  e.paths().stream()
                                      .anyMatch(ep -> EventPathMatcher.matches(ep, p))))
          .filter(
              e ->
                  query.commitRefs().isEmpty()
                      || query.commitRefs().stream()
                          .anyMatch(
                              c ->
                                  e.commitRefs().stream()
                                      .anyMatch(ec -> ec.startsWith(c) || c.startsWith(ec))))
          .filter(
              e ->
                  query.keywords().isEmpty()
                      || query.keywords().stream()
                          .anyMatch(k -> e.summary().toLowerCase().contains(k.toLowerCase())))
          .toList();
    }

    @Override
    public Optional<ProductEvent> find(String repo, String eventId) {
      return events.stream()
          .filter(e -> e.repo().equals(repo) && e.eventId().equals(eventId))
          .findFirst();
    }
  }
}
