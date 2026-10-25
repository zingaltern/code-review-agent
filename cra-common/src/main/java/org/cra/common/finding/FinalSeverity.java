package org.cra.common.finding;

/** 发布时最终严重度（05§1.1 final_severity：important|nit|pre-existing）。 */
public enum FinalSeverity {
  /** 重要缺陷：必须修 */
  IMPORTANT,
  /** 小问题：受 nit 预算约束 */
  NIT,
  /** pre-existing：变更前已存在，单独列出，不占 nit 预算、不阻塞（03§5） */
  PRE_EXISTING
}
