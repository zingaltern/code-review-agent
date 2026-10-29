package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval gate} 参数解析（T3.1：对已有报告离线重判，不重跑 LLM）。 */
final class GateArgsParser {

  /** gate 接受的全部旗标（含无值开关，与 EvalUsage 同步）。 */
  private static final List<String> FLAGS =
      List.of("--report", "--tier", "--json-out", "--sha256-out", "--crosschecker-enabled");

  private GateArgsParser() {}

  static EvalCliArgs.GateArgs parse(EvalArgOptions options) {
    options.rejectUnknownFlags(FLAGS);
    options.rejectPositional();
    Map<String, String> named = options.named();
    String report = named.get("--report");
    if (report == null || report.isBlank()) {
      throw new CraConfigException("缺参数: --report <json>（gate 对已有报告离线重判，T3.1）");
    }
    String tier = named.get("--tier");
    if (tier != null && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("gate --tier 非法: " + tier + "（l1|l2|l3）");
    }
    String jsonOut = named.get("--json-out");
    String sha256Out = named.get("--sha256-out");
    return new EvalCliArgs.GateArgs(
        Path.of(report),
        tier,
        jsonOut == null ? null : Path.of(jsonOut),
        sha256Out == null ? null : Path.of(sha256Out),
        "true".equals(named.get("--crosschecker-enabled")));
  }
}
