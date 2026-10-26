package org.cra.runner.cli;

import java.time.Duration;
import org.cra.common.exception.CraConfigException;

/** 夜间存量扫描窗口解析（M4d，05§3；形如 7d / 12h / 2w，与 cra-eval report 的 --since 同形）。 */
final class SweepScope {

  private SweepScope() {}

  /**
   * 解析相对时间窗口。
   *
   * @param value 形如 {@code 7d} / {@code 12h} / {@code 2w}
   * @return 时长
   */
  static Duration parseSince(String value) {
    if (value == null || !value.matches("\\d{1,4}[hdw]")) {
      throw new CraConfigException("--since 形如 7d / 12h / 2w: " + value);
    }
    int amount = Integer.parseInt(value.substring(0, value.length() - 1));
    if (amount <= 0) {
      throw new CraConfigException("--since 必须为正数: " + value);
    }
    return switch (value.charAt(value.length() - 1)) {
      case 'h' -> Duration.ofHours(amount);
      case 'd' -> Duration.ofDays(amount);
      default -> Duration.ofDays(amount * 7L);
    };
  }
}
