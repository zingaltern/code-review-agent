package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** 已切分的命令行参数（子命令 + 具名参数 + 位置参数）；无值开关出现即 true。 */
record EvalArgOptions(String subcommand, Map<String, String> named, List<String> positional) {

  /** 无值开关（出现即 true，不消费下一个参数）。 */
  private static final List<String> BOOLEAN_FLAGS =
      List.of("--fail-on-red", "--crosschecker-enabled");

  /** 编辑距离 ≤ 3 才给"最近似旗标"提示，避免噪声。 */
  private static final int SUGGEST_DISTANCE = 3;

  static EvalArgOptions parse(String[] args) {
    if (args.length == 0) {
      throw new CraConfigException(EvalUsage.text());
    }
    Map<String, String> named = new HashMap<>();
    List<String> positional = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--")) {
        if (BOOLEAN_FLAGS.contains(arg)) {
          named.put(arg, "true");
          continue;
        }
        if (i + 1 >= args.length) {
          throw new CraConfigException("参数缺值: " + arg);
        }
        named.put(arg, args[++i]);
      } else {
        positional.add(arg);
      }
    }
    return new EvalArgOptions(args[0], named, positional);
  }

  /**
   * 拒绝白名单之外的具名旗标（拼写错误快速失败，附最近似已知旗标提示）。
   *
   * @param allowedFlags 该子命令接受的全部旗标（含无值开关）
   */
  void rejectUnknownFlags(List<String> allowedFlags) {
    for (String flag : named.keySet().stream().sorted().toList()) {
      if (!allowedFlags.contains(flag)) {
        throw new CraConfigException(
            subcommand + " 未知旗标: " + flag + nearestFlagHint(flag, allowedFlags));
      }
    }
  }

  /** 拒绝位置参数（仅限不消费位置参数的子命令：run/verify/gate/report）。 */
  void rejectPositional() {
    if (!positional.isEmpty()) {
      throw new CraConfigException(
          subcommand + " 不接受位置参数: " + String.join(" ", positional) + "（只接受 --flag 值对）");
    }
  }

  private static String nearestFlagHint(String flag, List<String> allowedFlags) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : allowedFlags) {
      int distance = editDistance(flag, candidate);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return best == null || bestDistance > SUGGEST_DISTANCE ? "" : "（是否想用 " + best + "?）";
  }

  private static int editDistance(String a, String b) {
    int[][] dp = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }
}
