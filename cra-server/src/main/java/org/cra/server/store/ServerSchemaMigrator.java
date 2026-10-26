package org.cra.server.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.cra.common.exception.CraLedgerException;
import org.cra.store.api.StoreAssembly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端 schema 迁移（手写幂等 DDL，07§5 M4b）：启动时执行 cra-store 的五表 + server 的
 * delivery/attestation/schema_migration。
 *
 * <p>不引入 Flyway（ADR-0008）：DDL 只有两个版本且全为 IF NOT EXISTS，手写脚本可审计；schema_migration 仅登记已执行版本，不做回滚。
 */
public final class ServerSchemaMigrator {

  private static final Logger LOG = LoggerFactory.getLogger(ServerSchemaMigrator.class);
  private static final String SERVER_SCHEMA = "db/postgres/server.sql";
  private static final String SERVER_VERSION = "server-0001";

  private final DataSource dataSource;

  /**
   * @param dataSource 连接池
   */
  public ServerSchemaMigrator(DataSource dataSource) {
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource 不能为空");
    }
    this.dataSource = dataSource;
  }

  /** 执行迁移（Spring initMethod；重复执行安全）。 */
  public void migrate() {
    StoreAssembly.ensurePostgresSchema(dataSource);
    executeScript(readResource(SERVER_SCHEMA));
    recordVersion();
    LOG.info("server schema 就绪: ledger 五表 + {}（version={}）", SERVER_SCHEMA, SERVER_VERSION);
  }

  private void executeScript(String script) {
    try (Connection conn = dataSource.getConnection()) {
      for (String statement : script.split(";")) {
        String sql = statement.strip();
        if (sql.isEmpty()) {
          continue;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.execute();
        }
      }
    } catch (SQLException e) {
      throw new CraLedgerException("server schema 初始化失败: " + SERVER_SCHEMA, e);
    }
  }

  private void recordVersion() {
    String sql =
        "INSERT INTO schema_migration(version, applied_at) VALUES (?, now())"
            + " ON CONFLICT (version) DO NOTHING";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, SERVER_VERSION);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("schema_migration 登记失败", e);
    }
  }

  private static String readResource(String path) {
    try (InputStream in = ServerSchemaMigrator.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new CraLedgerException("缺少 SQL 资源: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CraLedgerException("SQL 资源读取失败: " + path, e);
    }
  }
}
