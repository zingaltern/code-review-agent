package org.cra.server.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.cra.common.event.EventHistoryStore;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
import org.cra.server.store.ServerSchemaMigrator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PG 事件史存储契约（08§3.1：CI service 容器经 {@code CRA_TEST_PG_URL}；本地无 PG 时跳过并给出原因）。
 *
 * <p>覆盖：幂等键 (repo,event_id) 零重复、重复采集覆盖计数、路径/commit 前缀/关键词/时间检索、水位只前进、 调度重复跑零重复。
 */
@EnabledIfEnvironmentVariable(named = "CRA_TEST_PG_URL", matches = ".+")
class PostgresEventHistoryStoreTest {

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  @TempDir Path dir;

  @BeforeAll
  static void migrate() {
    org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
    ds.setUrl(System.getenv("CRA_TEST_PG_URL"));
    ds.setUser(System.getenv().getOrDefault("CRA_TEST_PG_USER", "cra"));
    ds.setPassword(System.getenv().getOrDefault("CRA_TEST_PG_PASSWORD", "cra"));
    dataSource = ds;
    jdbc = new JdbcTemplate(ds);
    new ServerSchemaMigrator(ds).migrate();
  }

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE product_event, event_collection_checkpoint");
  }

  @Test
  void upsertIsIdempotentAndSupportsPathCommitKeywordTimeQueries() {
    PostgresEventHistoryStore store = new PostgresEventHistoryStore(dataSource);
    ProductEvent incident =
        event(
            "INC-42",
            "Owner/Repo",
            "billing",
            Instant.parse("2026-08-14T03:00:00Z"),
            "incident",
            "会话超时事故：SessionKeepAlive 企业长会话窗口 8h",
            List.of("abc1234deadbeef"),
            List.of("src/billing/**"));
    ProductEvent ticket =
        event(
            "T-7",
            "owner/repo",
            "reporting",
            Instant.parse("2026-09-01T00:00:00Z"),
            "ticket",
            "报表口径不一致",
            List.of("deadbeefcafe"),
            List.of("src/reports/Report.java"));
    ProductEvent other =
        event(
            "X-1",
            "other/repo",
            "billing",
            Instant.parse("2026-08-20T00:00:00Z"),
            "incident",
            "无关仓库事件",
            List.of(),
            List.of("src/billing"));

    EventHistoryStore.UpsertResult first = store.upsertAll(List.of(incident, ticket, other));
    assertThat(first.inserted()).isEqualTo(3);
    assertThat(first.updated()).isZero();
    assertThat(count()).isEqualTo(3);

    EventHistoryStore.UpsertResult second = store.upsertAll(List.of(incident, ticket, other));
    assertThat(second.inserted()).isZero();
    assertThat(second.updated()).isEqualTo(3);
    assertThat(count()).isEqualTo(3);

    List<ProductEvent> byPath =
        store.search(
            new EventQuery(
                "owner/repo",
                List.of("src/billing/Invoice.java"),
                List.of(),
                List.of(),
                null,
                null,
                20));
    assertThat(byPath).extracting(ProductEvent::eventId).containsExactly("INC-42");

    List<ProductEvent> byCommit =
        store.search(
            new EventQuery(
                "owner/repo",
                List.of(),
                List.of("abc1234deadbeef0000"),
                List.of(),
                null,
                null,
                20));
    assertThat(byCommit).extracting(ProductEvent::eventId).containsExactly("INC-42");

    List<ProductEvent> byKeyword =
        store.search(
            new EventQuery(
                "owner/repo", List.of(), List.of(), List.of("sessionkeepalive"), null, null, 20));
    assertThat(byKeyword).extracting(ProductEvent::eventId).containsExactly("INC-42");

    List<ProductEvent> byTime =
        store.search(
            new EventQuery(
                "owner/repo",
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-31T00:00:00Z"),
                20));
    assertThat(byTime).extracting(ProductEvent::eventId).containsExactly("INC-42");

    assertThat(store.find("OWNER/REPO", "INC-42")).isPresent();
    assertThat(store.find("other/repo", "INC-42")).isEmpty();

    assertThat(store.checkpoint("file://events.jsonl")).isEmpty();
    store.saveCheckpoint("file://events.jsonl", Instant.parse("2026-09-01T00:00:00Z"));
    store.saveCheckpoint("file://events.jsonl", Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(store.checkpoint("file://events.jsonl"))
        .contains(Instant.parse("2026-09-01T00:00:00Z"));
  }

  @Test
  void schedulerRepeatedRunsDoNotDuplicate() throws Exception {
    Path file = dir.resolve("events.jsonl");
    Files.writeString(
        file,
        """
        {"event_id":"INC-42","repo":"owner/repo","occurred_at":"2026-08-14T03:00:00Z","type":"incident","summary":"会话超时事故","paths":["src/billing/**"]}
        {"event_id":"T-7","repo":"owner/repo","occurred_at":"2026-09-01T00:00:00Z","type":"ticket","summary":"报表口径不一致"}
        """);
    PostgresEventHistoryStore store = new PostgresEventHistoryStore(dataSource);
    IncrementalEventHistoryCollector collector =
        new IncrementalEventHistoryCollector(
            new JsonlEventHistorySource(file.toString(), 1_000_000, java.time.Clock.systemUTC()),
            store,
            new EventCollectionSettings(365, 24, 3, 0, 1000));
    EventHistoryCollectionJob job = new EventHistoryCollectionJob(true, collector, 365);

    EventHistoryCollector.CollectionResult first = job.runOnce();
    EventHistoryCollector.CollectionResult second = job.runOnce();

    assertThat(first.executed()).isTrue();
    assertThat(first.inserted()).isEqualTo(2);
    assertThat(second.executed()).isTrue();
    assertThat(second.inserted()).isZero();
    assertThat(count()).isEqualTo(2);
    assertThat(store.search(EventQuery.of("owner/repo", Instant.EPOCH, 10))).hasSize(2);
  }

  private static int count() {
    Integer value = jdbc.queryForObject("SELECT count(*) FROM product_event", Integer.class);
    return value == null ? 0 : value;
  }

  private static ProductEvent event(
      String id,
      String repo,
      String service,
      Instant occurredAt,
      String type,
      String summary,
      List<String> commits,
      List<String> paths) {
    return new ProductEvent(
        id,
        repo,
        service,
        null,
        occurredAt,
        type,
        summary,
        "https://tracker/" + id,
        commits,
        paths,
        "{\"id\":\"" + id + "\"}",
        Instant.parse("2026-10-27T00:00:00Z"));
  }
}
