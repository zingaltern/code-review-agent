package org.cra.common.signoff;

import java.util.Locale;

/**
 * 测试状态输入（低风险"0 人签"的前置条件之一，07§5 M4a）：CI 形态默认 unknown（构建/测试是否通过由平台侧决定， 评审组件不假装知道），本地 pre-PR 形态由实跑结果给出
 * passed/failed。
 */
public enum TestsStatus {
  /** 测试通过（本地实跑退出码 0，或平台显式告知）。 */
  PASSED,
  /** 测试失败（本地实跑退出码非 0）。 */
  FAILED,
  /** 未知（CI 形态默认；低风险 0 人签条件不满足）。 */
  UNKNOWN;

  /**
   * 解析配置/环境变量取值（passed|failed|unknown；空 = unknown）。
   *
   * @param raw 原始文本
   * @return 状态
   * @throws IllegalArgumentException 取值非法
   */
  public static TestsStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "passed", "pass" -> PASSED;
      case "failed", "fail" -> FAILED;
      case "unknown" -> UNKNOWN;
      default ->
          throw new IllegalArgumentException("tests status must be passed|failed|unknown: " + raw);
    };
  }
}
