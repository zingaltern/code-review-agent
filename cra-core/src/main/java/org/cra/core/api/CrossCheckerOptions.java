package org.cra.core.api;

import java.util.Locale;
import java.util.Set;

/**
 * CrossChecker 触发配置（05§5 {@code agents.crosschecker}：enabled / mode / history-weeks）。
 *
 * <p>默认关闭（enabled=false，M4f-lite 起可开）——关闭时不改变 Standard 基线算术（04§2.2）；开启时 每次 run 增量 ≈20k token（330k →
 * ≈350k，按 500k 预算时代测算曾逼近 70% 降档线；预算 2026-10-31 翻倍至 1.6M 后余量更大）。
 *
 * @param enabled 总开关（false = 编排器不派发，Quick/Standard/Deep 一致）
 * @param modes 模式开关（03§3.1：ASSUMPTION 模式 A / SNIFF 模式 B；空集 = 开了总开关也不触发）
 * @param historyWeeks 上下游变更史回溯周数（05§5 history-weeks，默认 8）
 */
public record CrossCheckerOptions(boolean enabled, Set<Mode> modes, int historyWeeks) {

  /** CrossChecker 两模式（03§3.1）。 */
  public enum Mode {
    /** 模式 A：隐含假设核对（变更史 + Product context / Known incidents）。 */
    ASSUMPTION("assumption"),
    /** 模式 B：嗅探检查集执行（Sniff tests）。 */
    SNIFF("sniff");

    /** 配置串（--crosschecker-modes / 05§5 mode 列表）。 */
    public final String id;

    Mode(String id) {
      this.id = id;
    }

    /**
     * 解析配置串。
     *
     * @param raw 配置串（大小写不敏感）
     * @return 模式
     * @throws IllegalArgumentException 未知模式
     */
    public static Mode parse(String raw) {
      String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
      for (Mode mode : values()) {
        if (mode.id.equals(value)) {
          return mode;
        }
      }
      throw new IllegalArgumentException("未知 crosschecker 模式: " + raw + "（assumption|sniff）");
    }
  }

  public CrossCheckerOptions {
    modes = Set.copyOf(modes == null ? Set.of() : modes);
    if (historyWeeks <= 0) {
      throw new IllegalArgumentException("historyWeeks must be positive: " + historyWeeks);
    }
  }

  /**
   * 05§5 默认值：关闭 + 两模式全开 + 回溯 8 周（enabled=false 时其余字段不产生任何行为/算术影响）。
   *
   * @return 默认配置
   */
  public static CrossCheckerOptions defaults() {
    return new CrossCheckerOptions(false, Set.of(Mode.ASSUMPTION, Mode.SNIFF), 8);
  }

  /**
   * 未启用形态（兼容既有构造点）。
   *
   * @return enabled=false 的默认配置
   */
  public static CrossCheckerOptions disabled() {
    return defaults();
  }

  /**
   * 模式是否启用。
   *
   * @param mode 模式
   * @return true 若在配置集合内
   */
  public boolean modeEnabled(Mode mode) {
    return modes.contains(mode);
  }
}
