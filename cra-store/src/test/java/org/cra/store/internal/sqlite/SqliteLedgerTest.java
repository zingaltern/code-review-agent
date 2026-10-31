package org.cra.store.internal.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.ReviewLedgerContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite 账本单测（08§2.2：@TempDir 临时目录，每次测试新建库，无共享状态）。契约语义由 {@link ReviewLedgerContractTest} 共享，本类只补
 * SQLite 形态特有的原文检查（模板版本落库）。
 */
class SqliteLedgerTest extends ReviewLedgerContractTest {

  @TempDir private Path tempDir;

  @Override
  protected ReviewLedger newLedger() {
    return new SqliteLedger("o", "r", tempDir.resolve("ledger.db"));
  }

  @Test
  void constructorRejectsBlankRepoAndNullFile() {
    assertThatThrownBy(() -> new SqliteLedger(" ", "r", tempDir.resolve("x.db")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner/repo");
    assertThatThrownBy(() -> new SqliteLedger("o", "r", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dbFile");
  }

  @Test
  void templateVersionsRoundtripAndIdempotent() throws Exception {
    // prompt 模板版本随 run 落库（05§1.2/05§1.5）；同 run 重复登记幂等（INSERT OR REPLACE）
    Path db = tempDir.resolve("ledger.db");
    SqliteLedger ledger = new SqliteLedger("o", "r", db);
    ledger.recordTemplateVersions(7, "run_1", Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(7, "run_1", Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_2", java.util.Map.of("verifier", "balanced-review@deadbeef"));
    try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var rs =
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
