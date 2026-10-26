package org.cra.common.signoff;

import java.util.List;
import java.util.Objects;

/**
 * 一条命中的分级规则（07§5 M4a"分级依据可解释"）：规则 id + 风险等级 + 可读理由 + 证据（命中的路径等）。
 *
 * @param id 规则标识（稳定字符串，如 {@code high-risk-path}）
 * @param tier 该规则贡献的风险等级
 * @param reason 可读理由（中文，进 Summary 与本地报告）
 * @param evidence 证据列表（命中路径、作者/测试状态等；可为空）
 */
public record SignoffRuleHit(String id, RiskTier tier, String reason, List<String> evidence) {

  public SignoffRuleHit {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(reason, "reason");
    if (id.isBlank() || reason.isBlank()) {
      throw new IllegalArgumentException("rule id/reason must be non-blank");
    }
    evidence = List.copyOf(evidence == null ? List.of() : evidence);
  }
}
