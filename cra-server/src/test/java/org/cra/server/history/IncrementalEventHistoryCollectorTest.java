package org.cra.server.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.cra.common.event.EventHistoryStore;
import org.cra.common.event.EventPathMatcher;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
import org.junit.jupiter.api.Test;

/**
 * 采集器验收（ADR-0012）：幂等、增量窗口、有上限重试、失败如实声明、无数据优雅跳过、解析失败不推进水位。
 *
 * <p>存储用内存实现（不触 PG）；PG 幂等/检索在 {@code PostgresEventHistoryStoreTest}（CI service 容器）。
 */
class IncrementalEventHistoryCollectorTest {

  private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

  private static final EventCollectionSettings SETTINGS =
      new EventCollectionSettings(30, 24, 3, 0, 100);

  @Test
  void collectsAndSecondRunIsIdempotent() {
    InMemoryStore store = new InMemoryStore();
    ListSource source =
        new ListSource(
            List.of(
                event("T-1", "ticket", NOW.minusSeconds(2 * 24 * 3600)),
                event("INC-42", "incident", NOW.minusSeconds(24 * 3600))));
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult first =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));
    assertThat(first.executed()).isTrue();
    assertThat(first.inserted()).isEqualTo(2);
    assertThat(first.tickets()).isEqualTo(1);
    assertThat(first.incidents()).isEqualTo(1);
    assertThat(store.rows).hasSize(2);
    assertThat(store.checkpoints).containsEntry(source.id(), NOW.minusSeconds(24 * 3600));

    EventHistoryCollector.CollectionResult second =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));
    assertThat(second.executed()).isTrue();
    assertThat(second.inserted()).isZero();
    assertThat(second.updated()).isEqualTo(2);
    assertThat(store.rows).hasSize(2);
    assertThat(source.lastSince).isEqualTo(NOW.minusSeconds(2 * 24 * 3600));
  }

  @Test
  void incrementalWindowSkipsEventsOlderThanCheckpointOverlap() {
    InMemoryStore store = new InMemoryStore();
    ListSource source =
        new ListSource(
            List.of(
                event("OLD-1", "incident", NOW.minusSeconds(5 * 24 * 3600)),
                event("NEW-1", "incident", NOW.minusSeconds(3600))));
    store.saveCheckpoint(source.id(), NOW.minusSeconds(24 * 3600));
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(10 * 24 * 3600));

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(store.rows).containsOnlyKeys("owner/repo\u0000NEW-1");
    assertThat(source.lastSince).isEqualTo(NOW.minusSeconds(2 * 24 * 3600));
  }

  @Test
  void retriesSourceThenSucceeds() {
    InMemoryStore store = new InMemoryStore();
    ListSource source =
        new ListSource(List.of(event("INC-42", "incident", NOW.minusSeconds(3600))));
    source.failuresBeforeSuccess = 2;
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.executed()).isTrue();
    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.note()).contains("重试记录");
    assertThat(source.readCalls.get()).isEqualTo(3);
  }

  @Test
  void sourceFailureIsDeclaredNotFaked() {
    InMemoryStore store = new InMemoryStore();
    ListSource source = new ListSource(List.of(event("INC-42", "incident", NOW)));
    source.failuresBeforeSuccess = 99;
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.executed()).isFalse();
    assertThat(result.inserted()).isZero();
    assertThat(result.note()).contains("事件史采集失败").contains("重试 3 次");
    assertThat(store.rows).isEmpty();
  }

  @Test
  void storeFailureIsDeclaredNotFaked() {
    ListSource source =
        new ListSource(List.of(event("INC-42", "incident", NOW.minusSeconds(3600))));
    EventHistoryStore failingStore =
        new InMemoryStore() {
          @Override
          public UpsertResult upsertAll(List<ProductEvent> events) {
            throw new IllegalStateException("连接池耗尽");
          }
        };
    IncrementalEventHistoryCollector collector = collector(source, failingStore);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.executed()).isFalse();
    assertThat(result.note()).contains("入库失败").contains("幂等键");
  }

  @Test
  void noDataIsGraceful() {
    InMemoryStore store = new InMemoryStore();
    ListSource source = new ListSource(List.of());
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.executed()).isTrue();
    assertThat(result.fetched()).isZero();
    assertThat(result.inserted()).isZero();
    assertThat(result.note()).contains("无新事件");
    assertThat(store.checkpoints).isEmpty();
  }

  @Test
  void parseFailuresDoNotAdvanceWatermark() {
    InMemoryStore store = new InMemoryStore();
    ListSource source =
        new ListSource(List.of(event("INC-42", "incident", NOW.minusSeconds(3600))));
    source.failures.add("line 3: 缺少必填字段 event_id");
    IncrementalEventHistoryCollector collector = collector(source, store);

    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.executed()).isTrue();
    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.note()).contains("水位未推进");
    assertThat(store.checkpoints).isEmpty();
  }

  @Test
  void singleRunCapTruncatesAndWatermarkFollowsIngestedPrefix() {
    InMemoryStore store = new InMemoryStore();
    ListSource source =
        new ListSource(
            List.of(
                event("A-1", "incident", NOW.minusSeconds(3 * 3600)),
                event("B-2", "incident", NOW.minusSeconds(2 * 3600)),
                event("C-3", "incident", NOW.minusSeconds(3600))));
    IncrementalEventHistoryCollector collector =
        new IncrementalEventHistoryCollector(
            source, store, new EventCollectionSettings(30, 24, 1, 0, 2), millis -> {});
    EventHistoryCollector.CollectionResult result =
        collector.collect(NOW.minusSeconds(7 * 24 * 3600));

    assertThat(result.inserted()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.note()).contains("单次上限");
    assertThat(store.checkpoints).containsEntry(source.id(), NOW.minusSeconds(2 * 3600));
  }

  private static IncrementalEventHistoryCollector collector(
      EventHistorySource source, EventHistoryStore store) {
    return new IncrementalEventHistoryCollector(source, store, SETTINGS, millis -> {});
  }

  private static ProductEvent event(String id, String type, Instant occurredAt) {
    return new ProductEvent(
        id,
        "owner/repo",
        "billing",
        null,
        occurredAt,
        type,
        "事件 " + id,
        null,
        List.of(),
        List.of(),
        "{}",
        NOW);
  }

  /** 可注入失败/延迟的内存来源。 */
  private static final class ListSource implements EventHistorySource {

    private final List<ProductEvent> events;
    private final List<String> failures = new ArrayList<>();
    private final AtomicInteger readCalls = new AtomicInteger();
    private int failuresBeforeSuccess;
    private Instant lastSince;

    private ListSource(List<ProductEvent> events) {
      this.events = events;
    }

    @Override
    public String id() {
      return "memory://events";
    }

    @Override
    public ReadResult read(Instant since) throws IOException {
      int call = readCalls.incrementAndGet();
      if (call <= failuresBeforeSuccess) {
        throw new IOException("连接超时（第 " + call + " 次）");
      }
      lastSince = since;
      // 来源可以过滤，也可以不过滤——这里故意返回全量，验证采集器的窗口复核
      return new ReadResult(events, failures, events.size());
    }
  }

  /** 内存存储（幂等键语义与 PG 实现一致）。 */
  private static class InMemoryStore implements EventHistoryStore {

    private final Map<String, ProductEvent> rows = new LinkedHashMap<>();
    private final Map<String, Instant> checkpoints = new LinkedHashMap<>();

    @Override
    public UpsertResult upsertAll(List<ProductEvent> events) {
      int inserted = 0;
      int updated = 0;
      for (ProductEvent event : events) {
        String key = event.repo() + "\u0000" + event.eventId();
        if (rows.put(key, event) == null) {
          inserted++;
        } else {
          updated++;
        }
      }
      return new UpsertResult(inserted, updated);
    }

    @Override
    public Optional<Instant> checkpoint(String sourceId) {
      return Optional.ofNullable(checkpoints.get(sourceId));
    }

    @Override
    public void saveCheckpoint(String sourceId, Instant watermark) {
      checkpoints.merge(
          sourceId,
          watermark,
          (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing);
    }

    @Override
    public List<ProductEvent> search(EventQuery query) {
      return rows.values().stream()
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
          .limit(query.limit())
          .toList();
    }

    @Override
    public Optional<ProductEvent> find(String repo, String eventId) {
      return Optional.ofNullable(rows.get(repo + "\u0000" + eventId));
    }
  }
}
