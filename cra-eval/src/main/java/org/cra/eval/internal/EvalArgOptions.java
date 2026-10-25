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
}
