package org.cra.server.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.cra.common.event.ProductEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JSONL 事件源（ADR-0012）：字段映射、窗口过滤、行级失败不中断整批。 */
class JsonlEventHistorySourceTest {

  private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

  private static final String FIXTURE =
      """
      {"event_id":"INC-42","repo":"Owner/Repo","service":"billing","tenant":"acme","occurred_at":"2026-08-14T03:00:00Z","type":"incident","summary":"会话超时事故：企业长会话窗口被改为 8h","source_url":"https://tracker/INC-42","commit_refs":["ABC1234","deadbeef"],"paths":["src/billing/**"]}
      {"event_id":"T-1","repo":"owner/repo","occurred_at":"2026-07-01T00:00:00Z","type":"ticket","summary":"旧工单"}
      not-json
      {"event_id":"C-1","repo":"owner/repo","occurred_at":"2026-09-01T00:00:00+08:00","type":"change","summary":"变更","paths":"src/foo/,src/bar"}
      {"repo":"owner/repo","occurred_at":"2026-09-02T00:00:00Z","type":"ticket","summary":"缺 id"}
      """;

  @TempDir Path dir;

  @Test
  void parsesEventsFiltersWindowAndReportsBadLines() throws Exception {
    Path file = dir.resolve("events.jsonl");
    Files.writeString(file, FIXTURE);
    JsonlEventHistorySource source =
        new JsonlEventHistorySource(file.toString(), 1_000_000, Clock.fixed(NOW, ZoneOffset.UTC));

    EventHistorySource.ReadResult result = source.read(Instant.parse("2026-08-01T00:00:00Z"));

    assertThat(result.linesRead()).isEqualTo(5);
    assertThat(result.events()).extracting(ProductEvent::eventId).containsExactly("INC-42", "C-1");
    assertThat(result.failures()).hasSize(2);
    assertThat(result.failures().get(0)).contains("line 3");
    assertThat(result.failures().get(1)).contains("event_id");

    ProductEvent incident = result.events().get(0);
    assertThat(incident.repo()).isEqualTo("owner/repo");
    assertThat(incident.tenant()).isEqualTo("acme");
    assertThat(incident.commitRefs()).containsExactly("abc1234", "deadbeef");
    assertThat(incident.paths()).containsExactly("src/billing");
    assertThat(incident.rawJson()).contains("\"event_id\":\"INC-42\"");
    assertThat(incident.collectedAt()).isEqualTo(NOW);

    ProductEvent change = result.events().get(1);
    assertThat(change.occurredAt()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
    assertThat(change.paths()).containsExactly("src/bar", "src/foo");
  }

  @Test
  void sourceIdIsStableLocation() {
    JsonlEventHistorySource source =
        new JsonlEventHistorySource("/tmp/events.jsonl", 1024, Clock.systemUTC());
    assertThat(source.id()).isEqualTo("/tmp/events.jsonl");
  }

  @Test
  void oversizedPayloadFailsLoudly() throws Exception {
    Path file = dir.resolve("big.jsonl");
    Files.writeString(file, "x".repeat(2048));
    JsonlEventHistorySource source =
        new JsonlEventHistorySource(file.toString(), 1024, Clock.systemUTC());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.read(Instant.EPOCH))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("上限");
  }
}
