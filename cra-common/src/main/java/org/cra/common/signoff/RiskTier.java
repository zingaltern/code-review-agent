package org.cra.common.signoff;

/**
 * 变更风险分级（07§5 M4a；01§3.4 分级表）：低 = 纯文档/功能开关，中 = 常规业务逻辑与系统提示词， 高 = 核心高危路径与难恢复的关键配置。
 *
 * <p>分级只用于生成<strong>签字建议</strong>，不构成审批（agent 永远不是 approver）。
 */
public enum RiskTier {
  /** 低风险：建议 0 人签字（前提 = 测试全过 + 可信作者）。 */
  LOW(0, "低风险"),
  /** 中风险：建议 1 人签字（常规业务逻辑、系统提示词）。 */
  MEDIUM(1, "中风险"),
  /** 高风险：建议 2 人签字（核心高危路径、难恢复的关键配置）。 */
  HIGH(2, "高风险");

  private final int recommendedSigners;
  private final String displayName;

  RiskTier(int recommendedSigners, String displayName) {
    this.recommendedSigners = recommendedSigners;
    this.displayName = displayName;
  }

  /**
   * 该等级建议的签字人数。
   *
   * @return 0/1/2（01§3.4 分级表）
   */
  public int recommendedSigners() {
    return recommendedSigners;
  }

  /**
   * 中文展示名（Summary 与本地报告使用）。
   *
   * @return 低风险/中风险/高风险
   */
  public String displayName() {
    return displayName;
  }

  /**
   * 取两者中较高的风险等级（多规则命中时取最大值）。
   *
   * @param left 等级（可为 null）
   * @param right 等级（可为 null）
   * @return 较高者；两者皆 null 时返回 null
   */
  public static RiskTier max(RiskTier left, RiskTier right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.ordinal() >= right.ordinal() ? left : right;
  }
}
