package org.cra.server.attest;

/** 服务端已知 commit 检查（签发策略：只对服务端完成过评审的 commit 签名，ADR-0008）。 */
public interface KnownCommitChecker {

  /**
   * 该 commit 是否已有完成的评审 run（DONE|DEGRADED）。
   *
   * @param commitSha head sha
   * @return true = 已知
   */
  boolean exists(String commitSha);
}
