package org.cra.store.internal.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PG 测试数据源（08§3.1 M4b 修订）：优先读 {@code CRA_TEST_PG_URL}（CI service 容器），否则用 Testcontainers 启
 * postgres:16； 两者都不可得时测试以 assumption 跳过并给出明确原因（不静默绿——跳过有日志）。
 *
 * <p>本类只在 PG 测试类加载时初始化；SQLite 测试不受 Docker 依赖影响。
 */
final class PostgresTestSupport {

  private static final String ENV_URL = "CRA_TEST_PG_URL";
  private static final String ENV_USER = "CRA_TEST_PG_USER";
  private static final String ENV_PASSWORD = "CRA_TEST_PG_PASSWORD";
  private static final String TESTCONTAINER_IMAGE = "postgres:16-alpine";

  private static final DataSource DATA_SOURCE = init();
  private static String unavailableReason;

  private PostgresTestSupport() {}

  private static DataSource init() {
    String url = System.getenv(ENV_URL);
    if (url != null && !url.isBlank()) {
      org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
      ds.setUrl(url);
      ds.setUser(System.getenv().getOrDefault(ENV_USER, "cra"));
      ds.setPassword(System.getenv().getOrDefault(ENV_PASSWORD, "cra"));
      return ds;
    }
    try {
      PostgreSQLContainer<?> container =
          new PostgreSQLContainer<>(TESTCONTAINER_IMAGE).withReuse(true);
      container.start();
      org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
      ds.setUrl(container.getJdbcUrl());
      ds.setUser(container.getUsername());
      ds.setPassword(container.getPassword());
      return ds;
    } catch (RuntimeException e) {
      unavailableReason =
          "无 PG：" + ENV_URL + " 未设置且 Testcontainers 启动失败（Docker 不可用？）：" + e.getMessage();
      System.err.println(unavailableReason);
      return null;
    }
  }

  private static String reason() {
    return unavailableReason;
  }

  static void assumeAvailable() {
    Assumptions.assumeTrue(DATA_SOURCE != null, reason());
  }

  static DataSource dataSource() {
    if (DATA_SOURCE == null) {
      throw new IllegalStateException(reason());
    }
    return DATA_SOURCE;
  }

  static void truncateAll() {
    try (Connection conn = dataSource().getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          "TRUNCATE review_run, finding, verdict, published_comment, prompt_template_version");
    } catch (SQLException e) {
      throw new IllegalStateException("PG 测试表清理失败", e);
    }
  }
}
