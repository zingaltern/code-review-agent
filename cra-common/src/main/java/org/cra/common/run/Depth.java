package org.cra.common.run;

/** 评审深度档位（02§4.3：quick/standard/deep，触发与实例数见表）。 */
public enum Depth {
  /** label cra:quick 或默认小 diff（<300 行） */
  QUICK,
  /** 默认档 */
  STANDARD,
  /** label cra:deep */
  DEEP
}
