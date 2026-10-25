package org.cra.eval.internal;

import org.cra.common.exception.CraConfigException;

/**
 * 门禁档位（04§2.4 三级分级；06§1.2 阈值分档维护）。
 *
 * <p>档位决定阈值、样本面与执行位置：L1 = 每 PR 快检（对抗微型 fixture 子集、k=1、CI 内、红了只升级）； L2 = prompt/模型/编排改动复核（bad 12 ×
 * k=2 quick、本地跑 + CI 校验报告哈希）；L3 = 里程碑/大改全量（k=3 standard）。 各档独立维护基线，<strong>禁止跨档比较</strong>（06§1.2）。
 */
enum GateTier {
  /** 每 PR 快检：注入 3 + 幻觉 2，k=1，quick。 */
  L1("l1", "L1 快检", 1, "quick", "注入 3 + 幻觉 2，k=1 quick，CI 内红了只升级不阻塞（04§2.4）"),
  /** 标准复核：bad 12 × k=2 quick（24 观测），本地跑 + CI 校验报告哈希。 */
  L2("l2", "L2 标准复核", 2, "quick", "bad 12 × k=2 quick = 24 观测，本地跑 + CI 校验哈希（04§2.4）"),
  /** 里程碑全量：bad ≥12 × k=3 standard。 */
  L3("l3", "L3 里程碑全量", 3, "standard", "全量数据集 × k=3 standard（M2/M3 验收口径）");

  private final String id;
  private final String label;
  private final int expectedK;
  private final String expectedDepth;
  private final String profileNote;

  GateTier(String id, String label, int expectedK, String expectedDepth, String profileNote) {
    this.id = id;
    this.label = label;
    this.expectedK = expectedK;
    this.expectedDepth = expectedDepth;
    this.profileNote = profileNote;
  }

  /**
   * @return JSON 中的档位标识
   */
  String id() {
    return id;
  }

  /**
   * @return 报告展示名
   */
  String label() {
    return label;
  }

  /**
   * @return 该档规定的每样本运行次数
   */
  int expectedK() {
    return expectedK;
  }

  /**
   * @return 该档规定的深度
   */
  String expectedDepth() {
    return expectedDepth;
  }

  /**
   * @return 口径说明（报告 detail 用）
   */
  String profileNote() {
    return profileNote;
  }

  /**
   * 由 k 与 depth 推导档位（--tier 未显式指定时）。
   *
   * @param k 每样本运行次数
   * @param depth 深度档位
   * @return 推导档位（k≥3 → L3；k≥2 → L2；否则 L1——与 04§2.4 成本分级一致）
   */
  static GateTier resolve(int k, String depth) {
    if (k >= 3 || "standard".equals(depth) || "deep".equals(depth)) {
      return L3;
    }
    return k >= 2 ? L2 : L1;
  }

  /**
   * 解析 --tier 参数（auto 走 {@link #resolve}）。
   *
   * @param value 参数值（auto|l1|l2|l3，缺省 auto）
   * @param k 每样本运行次数
   * @param depth 深度档位
   * @return 档位
   * @throws CraConfigException 非法取值
   */
  static GateTier parse(String value, int k, String depth) {
    if (value == null || value.isBlank() || "auto".equals(value)) {
      return resolve(k, depth);
    }
    return switch (value) {
      case "l1" -> L1;
      case "l2" -> L2;
      case "l3" -> L3;
      default -> throw new CraConfigException("--tier 非法: " + value + "（auto|l1|l2|l3，04§2.4）");
    };
  }
}
