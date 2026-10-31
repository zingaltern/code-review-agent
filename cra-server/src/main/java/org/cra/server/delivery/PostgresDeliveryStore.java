package org.cra.server.delivery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.cra.common.exception.CraLedgerException;

/**
 * PostgreSQL delivery 登记（幂等键 = delivery id；ON CONFLICT DO NOTHING 保证同 delivery 并发到达时只有一次 claim 成功）。
 *
 * <p>表由 {@code db/postgres/server.sql} 的幂等 DDL 创建（server 启动时由 {@code ServerSchemaMigrator} 执行）。
 */
public final class PostgresDeliveryStore implements DeliveryStore {

  private static final int DETAIL_LIMIT = 2_000;

  private final DataSource dataSource;

  /**
   * @param dataSource 连接池
   */
  public PostgresDeliveryStore(DataSource dataSource) {
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource 不能为空");
    }
    this.dataSource = dataSource;
  }

  @Override
  public boolean claim(DeliveryRecord delivery) {
    String sql =
        "INSERT INTO webhook_delivery(delivery_id, repo, pr_number, head_sha, event_action, status,"
            + " run_id, detail, received_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (delivery_id) DO NOTHING";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, delivery.deliveryId());
      ps.setString(2, delivery.repo());
      ps.setInt(3, delivery.prNumber());
      ps.setString(4, delivery.headSha());
      ps.setString(5, delivery.eventAction());
      ps.setString(6, delivery.status());
      ps.setString(7, delivery.runId());
      ps.setString(8, delivery.detail());
      ps.setTimestamp(9, Timestamp.from(delivery.receivedAt()));
      ps.setTimestamp(10, Timestamp.from(delivery.updatedAt()));
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new CraLedgerException("delivery claim 失败: " + delivery.deliveryId(), e);
    }
  }

  @Override
  public Optional<DeliveryRecord> find(String deliveryId) {
    String sql =
        "SELECT delivery_id, repo, pr_number, head_sha, event_action, status, run_id, detail,"
            + " received_at, updated_at FROM webhook_delivery WHERE delivery_id = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, deliveryId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new DeliveryRecord(
                rs.getString("delivery_id"),
                rs.getString("repo"),
                rs.getInt("pr_number"),
                rs.getString("head_sha"),
                rs.getString("event_action"),
                rs.getString("status"),
                rs.getString("run_id"),
                rs.getString("detail"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
      }
    } catch (SQLException e) {
      throw new CraLedgerException("delivery 查询失败: " + deliveryId, e);
    }
  }

  @Override
  public void markProcessing(String deliveryId) {
    update(
        "UPDATE webhook_delivery SET status = ?, updated_at = ? WHERE delivery_id = ? AND status = ?",
        DeliveryRecord.PROCESSING,
        Timestamp.from(Instant.now()),
        deliveryId,
        DeliveryRecord.RECEIVED);
  }

  @Override
  public void markCompleted(String deliveryId, String runId, String detail) {
    update(
        "UPDATE webhook_delivery SET status = ?, run_id = ?, detail = ?, updated_at = ?"
            + " WHERE delivery_id = ?",
        DeliveryRecord.COMPLETED,
        runId,
        truncate(detail),
        Timestamp.from(Instant.now()),
        deliveryId);
  }

  @Override
  public void markFailed(String deliveryId, String detail) {
    update(
        "UPDATE webhook_delivery SET status = ?, detail = ?, updated_at = ? WHERE delivery_id = ?",
        DeliveryRecord.FAILED,
        truncate(detail),
        Timestamp.from(Instant.now()),
        deliveryId);
  }

  @Override
  public void release(String deliveryId) {
    update(
        "DELETE FROM webhook_delivery WHERE delivery_id = ? AND status = ?",
        deliveryId,
        DeliveryRecord.RECEIVED);
  }

  @Override
  public boolean resetFailed(String deliveryId) {
    String sql =
        "UPDATE webhook_delivery SET status = ?, detail = NULL, updated_at = ?"
            + " WHERE delivery_id = ? AND status = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, DeliveryRecord.RECEIVED);
      ps.setTimestamp(2, Timestamp.from(Instant.now()));
      ps.setString(3, deliveryId);
      ps.setString(4, DeliveryRecord.FAILED);
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new CraLedgerException("delivery 重试重置失败: " + deliveryId, e);
    }
  }

  private void update(String sql, Object... args) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("delivery 状态更新失败: " + sql, e);
    }
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= DETAIL_LIMIT ? value : value.substring(0, DETAIL_LIMIT);
  }
}
