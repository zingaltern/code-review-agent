package org.cra.server.attest;

/** 报告签发记录落库端口（生产 = PG；测试 = 内存）。 */
public interface AttestationStore {

  /**
   * 记录一次签发。
   *
   * @param attestation 签发记录
   */
  void recordIssued(AttestationRecord attestation);
}
