package org.cra.common.finding;

/** 提议严重度（05§1.1 severity_proposed：important|nit）。 */
public enum Severity {
  /** 重要缺陷：必须修 */
  IMPORTANT,
  /** 小问题：受 nit 预算约束（02§4 聚合器） */
  NIT
}
