package org.cra.store.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.ReviewLedgerContractTest;
import org.cra.store.api.StoreAssembly;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 账本契约测试（08§3.1 M4b：优先 CI service 容器，其次 Testcontainers；无 PG 时 assumption 跳过）。
 *
 * <p>契约语义由 {@link ReviewLedgerContractTest} 继承，本类补 PG 形态特有的五表存在性与模板版本落库。
 */
class PostgresLedgerContractTest extends ReviewLedgerContractTest {

  @BeforeAll
  static void ensureSchema() {
    PostgresTestSupport.assumeAvailable();
    new PostgresLedger("o", "r", PostgresTestSupport.dataSource()).ensureSchema();
  }

  @BeforeEach
  void cleanTables() {
    PostgresTestSupport.truncateAll();
  }

  @Override
  protected ReviewLedger newLedger() {
    return StoreAssembly.createPostgresLedger("o", "r", PostgresTestSupport.dataSource());
  }

  @Test
  void schemaCreatesFiveTablesFromDoc() throws Exception {
    try (Connection conn = PostgresTestSupport.dataSource().getConnection();
        ResultSet rs =
            conn.createStatement()
                .executeQuery(
                    "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'public' ORDER BY table_name")) {
      List<String> tables = new ArrayList<>();
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
      assertThat(tables)
          .contains(
              "review_run", "finding", "verdict", "published_comment", "prompt_template_version");
    }
  }

  @Test
  void templateVersionsRoundtripAndIdempotent() throws Exception {
    ReviewLedger ledger = newLedger();
    ledger.recordTemplateVersions(
        7, "run_1", java.util.Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_1", java.util.Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_2", java.util.Map.of("verifier", "balanced-review@deadbeef"));
    try (Connection conn = PostgresTestSupport.dataSource().getConnection();
        ResultSet rs =
            conn.createStatement()
                .executeQuery(
                    "SELECT template_name, template_version FROM prompt_template_version"
                        + " WHERE repo = 'o/r' ORDER BY run_id, template_name")) {
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + "=" + rs.getString(2));
      }
      assertThat(rows)
          .containsExactly(
              "general-reviewer=general@1a2b3c04", "verifier=balanced-review@deadbeef");
    }
  }
}
