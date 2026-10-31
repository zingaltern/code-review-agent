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
  public void recordIssued(AttestationRecord attestation) {
    String sql =
        "INSERT INTO report_attestation(attestation_id, report_sha256, repo, commit_sha, tier, key_id,"
            + " issued_at, requested_by, signature) VALUES (?,?,?,?,?,?,?,?,?)";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, attestation.attestationId());
      ps.setString(2, attestation.reportSha256());
      ps.setString(3, attestation.repo());
      ps.setString(4, attestation.commitSha());
      ps.setString(5, attestation.tier());
      ps.setString(6, attestation.keyId());
      ps.setTimestamp(7, Timestamp.from(attestation.issuedAt()));
      ps.setString(8, attestation.requestedBy());
      ps.setString(9, attestation.signature());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("签发记录落库失败: " + attestation.attestationId(), e);
    }
  }
}
