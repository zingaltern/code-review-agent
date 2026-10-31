package org.cra.server.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.cra.common.event.EventHistoryStore;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL 事件史存储（M4f-full，ADR-0012；05§1.6 的 {@code product_event} + 水位表）。
 *
 * <p>幂等键 {@code (repo, event_id)}：重复入库按覆盖语义更新内容，绝不产生第二行；批内同键以后出现者为准。检索端口同时服务
 * CrossChecker（路径/commit/关键词/时间）与验证层的 {@code event <id>} 引用解析。
 *
 * <p>仓库名统一小写存储与查询（平台仓库名大小写不敏感）；commit 前缀互配（7 位短 sha 与 40 位完整 sha 都能命中）。
 */
public final class PostgresEventHistoryStore implements EventHistoryStore {

  /** 单条 SQL 的 IN 列表分片上限（避免超长语句/参数上限）。 */
  private static final int IN_CHUNK = 500;

  private static final String COLUMNS =
      "event_id, repo, service, tenant, occurred_at, type, summary, source_url,"
          + " commit_refs, paths, raw_json, collected_at";

  private static final String UPSERT_SQL =
      "INSERT INTO product_event (event_id, repo, service, tenant, occurred_at, type, summary,"
          + " source_url, commit_refs, paths, raw_json, collected_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)"
          + " ON CONFLICT (repo, event_id) DO UPDATE SET"
          + " service = EXCLUDED.service, tenant = EXCLUDED.tenant,"
          + " occurred_at = EXCLUDED.occurred_at, type = EXCLUDED.type,"
          + " summary = EXCLUDED.summary, source_url = EXCLUDED.source_url,"
          + " commit_refs = EXCLUDED.commit_refs, paths = EXCLUDED.paths,"
          + " raw_json = EXCLUDED.raw_json, collected_at = EXCLUDED.collected_at";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  /**
   * @param dataSource PG 连接池（与 M4b 账本共用）
   */
  public PostgresEventHistoryStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  @Override
  public UpsertResult upsertAll(List<ProductEvent> events) {
    if (events == null || events.isEmpty()) {
      return new UpsertResult(0, 0);
    }
    Map<String, ProductEvent> unique = new LinkedHashMap<>();
    for (ProductEvent event : events) {
      unique.put(event.repo() + "\u0000" + event.eventId(), event);
    }
    List<ProductEvent> rows = new ArrayList<>(unique.values());
    Set<String> existing = existingKeys(rows);
    jdbc.batchUpdate(
        UPSERT_SQL,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ProductEvent event = rows.get(i);
            ps.setString(1, event.eventId());
            ps.setString(2, event.repo());
            ps.setString(3, event.service());
            ps.setString(4, event.tenant());
            ps.setTimestamp(5, Timestamp.from(event.occurredAt()));
            ps.setString(6, event.type());
            ps.setString(7, event.summary());
            ps.setString(8, event.sourceUrl());
            ps.setString(9, json(event.commitRefs()));
            ps.setString(10, json(event.paths()));
            ps.setString(11, event.rawJson().isBlank() ? "{}" : event.rawJson());
            ps.setTimestamp(12, Timestamp.from(event.collectedAt()));
          }

          @Override
          public int getBatchSize() {
            return rows.size();
          }
        });
    int updated = existing.size();
    return new UpsertResult(rows.size() - updated, updated);
  }

  @Override
  public Optional<java.time.Instant> checkpoint(String sourceId) {
    List<java.time.Instant> rows =
        jdbc.query(
            "SELECT watermark FROM event_collection_checkpoint WHERE source_id = ?",
            (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
            sourceId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void saveCheckpoint(String sourceId, java.time.Instant watermark) {
    jdbc.update(
        "INSERT INTO event_collection_checkpoint (source_id, watermark, updated_at)"
            + " VALUES (?, ?, now())"
            + " ON CONFLICT (source_id) DO UPDATE SET"
            + " watermark = GREATEST(event_collection_checkpoint.watermark, EXCLUDED.watermark),"
            + " updated_at = now()",
        sourceId,
        Timestamp.from(watermark));
  }

  @Override
  public List<ProductEvent> search(EventQuery query) {
    if (query == null) {
      throw new IllegalArgumentException("query 不能为空");
    }
    StringBuilder sql =
        new StringBuilder("SELECT ").append(COLUMNS).append(" FROM product_event WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (query.repo() != null) {
      sql.append(" AND repo = ?");
      args.add(query.repo());
    }
    if (query.since() != null) {
      sql.append(" AND occurred_at >= ?");
      args.add(Timestamp.from(query.since()));
    }
    if (query.until() != null) {
      sql.append(" AND occurred_at <= ?");
      args.add(Timestamp.from(query.until()));
    }
    appendCommitFilter(sql, args, query.commitRefs());
    appendPathFilter(sql, args, query.paths());
    appendKeywordFilter(sql, args, query.keywords());
    sql.append(" ORDER BY occurred_at DESC, event_id ASC LIMIT ?");
    args.add(query.limit());
    return jdbc.query(sql.toString(), (rs, rowNum) -> mapRow(rs), args.toArray());
  }

  @Override
  public Optional<ProductEvent> find(String repo, String eventId) {
    if (repo == null || eventId == null) {
      return Optional.empty();
    }
    List<ProductEvent> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM product_event WHERE repo = ? AND event_id = ?",
            (rs, rowNum) -> mapRow(rs),
            repo.strip().toLowerCase(java.util.Locale.ROOT),
            eventId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static void appendCommitFilter(
      StringBuilder sql, List<Object> args, List<String> commitRefs) {
    if (commitRefs.isEmpty()) {
      return;
    }
    sql.append(" AND (");
    for (int i = 0; i < commitRefs.size(); i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("EXISTS (SELECT 1 FROM jsonb_array_elements_text(commit_refs) AS c(v)");
      sql.append(" WHERE lower(c.v) LIKE lower(?) || '%' OR lower(?) LIKE lower(c.v) || '%')");
      args.add(commitRefs.get(i));
      args.add(commitRefs.get(i));
    }
    sql.append(')');
  }

  private static void appendPathFilter(StringBuilder sql, List<Object> args, List<String> paths) {
    if (paths.isEmpty()) {
      return;
    }
    sql.append(" AND (");
    for (int i = 0; i < paths.size(); i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("EXISTS (SELECT 1 FROM jsonb_array_elements_text(paths) AS p(v)");
      sql.append(" WHERE ? = p.v OR ? LIKE p.v || '/%')");
      args.add(paths.get(i));
      args.add(paths.get(i));
    }
    sql.append(')');
  }

  private static void appendKeywordFilter(
      StringBuilder sql, List<Object> args, List<String> keywords) {
    if (keywords.isEmpty()) {
      return;
    }
    sql.append(" AND (");
    for (int i = 0; i < keywords.size(); i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("(summary ILIKE ? ESCAPE '\\' OR raw_json::text ILIKE ? ESCAPE '\\')");
      String pattern = "%" + escapeLike(keywords.get(i)) + "%";
      args.add(pattern);
      args.add(pattern);
    }
    sql.append(')');
  }

  private Set<String> existingKeys(List<ProductEvent> rows) {
    Map<String, List<String>> byRepo =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    ProductEvent::repo,
                    Collectors.mapping(ProductEvent::eventId, Collectors.toList())));
    Set<String> found = new LinkedHashSet<>();
    for (Map.Entry<String, List<String>> entry : byRepo.entrySet()) {
      List<String> ids = entry.getValue();
      for (int start = 0; start < ids.size(); start += IN_CHUNK) {
        List<String> chunk = ids.subList(start, Math.min(start + IN_CHUNK, ids.size()));
        // SQL 文本无动态拼接：IN 列表占位由 NamedParameterJdbcTemplate 按集合大小展开，
        // 仓库名与事件 id 全部走绑定参数，用户输入不直达 SQL 文本
        List<String> existing =
            namedJdbc.queryForList(
                "SELECT event_id FROM product_event WHERE repo = :repo AND event_id IN (:ids)",
                Map.of("repo", entry.getKey(), "ids", chunk),
                String.class);
        existing.forEach(id -> found.add(entry.getKey() + "\u0000" + id));
      }
    }
    return found;
  }

  private static ProductEvent mapRow(java.sql.ResultSet rs) throws SQLException {
    return new ProductEvent(
        rs.getString("event_id"),
        rs.getString("repo"),
        rs.getString("service"),
        rs.getString("tenant"),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getString("type"),
        rs.getString("summary"),
        rs.getString("source_url"),
        jsonList(rs.getString("commit_refs")),
        jsonList(rs.getString("paths")),
        rs.getString("raw_json"),
        rs.getTimestamp("collected_at").toInstant());
  }

  private static String json(List<String> values) {
    try {
      return MAPPER.writeValueAsString(values == null ? List.of() : values);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("事件字段序列化失败", e);
    }
  }

  private static List<String> jsonList(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      return MAPPER.readValue(raw, new TypeReference<List<String>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
