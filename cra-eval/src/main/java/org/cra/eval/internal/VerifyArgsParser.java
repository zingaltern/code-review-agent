package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/** {@code cra-eval verify} 参数解析（T3.3：CI 校验本地 L2 报告）。 */
final class VerifyArgsParser {

  private VerifyArgsParser() {}

  static EvalCliArgs.VerifyArgs parse(EvalArgOptions options) {
    Map<String, String> named = options.named();
    String report = named.get("--report");
    if (report == null || report.isBlank()) {
      throw new CraConfigException("缺参数: --report <json>（verify 校验本地 L2 报告，04§2.4）");
    }
    String sha256 = named.get("--sha256");
    String sha256File = named.get("--sha256-file");
    if ((sha256 == null || sha256.isBlank()) && (sha256File == null || sha256File.isBlank())) {
      throw new CraConfigException("缺参数: --sha256 <hex> 或 --sha256-file <path>（二选一）");
    }
    String tier = named.get("--tier");
    if (tier != null && !"l1".equals(tier) && !"l2".equals(tier) && !"l3".equals(tier)) {
      throw new CraConfigException("verify --tier 非法: " + tier + "（l1|l2|l3）");
    }
    return new EvalCliArgs.VerifyArgs(
        Path.of(report),
        sha256,
        sha256File == null ? null : Path.of(sha256File),
        tier,
        named.get("--expect-commit"));
  }
}
