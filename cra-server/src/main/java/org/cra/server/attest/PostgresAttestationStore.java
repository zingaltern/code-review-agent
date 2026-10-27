package org.cra.server.attest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.cra.common.exception.CraLedgerException;

/** PostgreSQL 签发记录（表 report_attestation，server.sql）。 */
public final class PostgresAttestationStore implements AttestationStore {

  private final DataSource dataSource;

  /**
   * @param dataSource 连接池
   */
  public PostgresAttestationStore(DataSource dataSource) {
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource 不能为空");
    }
    this.dataSource = dataSource;
  }

  @Override
  public void record(AttestationRecord record) {
    String sql =
        "INSERT INTO report_attestation(attestation_id, report_sha256, repo, commit_sha, tier, key_id,"
            + " issued_at, requested_by, signature) VALUES (?,?,?,?,?,?,?,?,?)";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, record.attestationId());
      ps.setString(2, record.reportSha256());
      ps.setString(3, record.repo());
      ps.setString(4, record.commitSha());
      ps.setString(5, record.tier());
      ps.setString(6, record.keyId());
      ps.setTimestamp(7, Timestamp.from(record.issuedAt()));
      ps.setString(8, record.requestedBy());
      ps.setString(9, record.signature());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("签发记录落库失败: " + record.attestationId(), e);
    }
  }
}
