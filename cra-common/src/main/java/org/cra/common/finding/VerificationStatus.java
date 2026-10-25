package org.cra.common.finding;

/** 复核判定状态（05§1.1 verification.status、02§4.2）。M0 单 reviewer 直发布，字段预留。 */
public enum VerificationStatus {
  /** 复核确认，发布 */
  CONFIRMED,
  /** 被推翻，丢弃（trace 留档，进拒真统计） */
  REJECTED,
  /** 验证不可得：默认不发布（trace 留档，Summary 计数声明，02§4.2） */
  UNVERIFIED
}
