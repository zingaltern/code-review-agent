package org.cra.common.context;

import java.util.List;
import java.util.Optional;

/**
 * 仓库评审策略（base 分支 REVIEW.md 解析结果，04§3；三类新段落 v1.3——Product context / Known incidents / Sniff tests，
 * 段落缺失时相关检查优雅跳过）。解析失败按最小默认策略运行 + warnings 警告（04§3.2 规则 4，不阻塞评审）。
 *
 * @param present base 分支是否存在可读取的 REVIEW.md
 * @param warnings 解析警告（进 Summary 覆盖声明；如"无法识别评审策略段落"）
 * @param nitBudget 单次评审 nit 上限（04§3.1；empty = 未配置，用部署默认；0 = 禁止）
 * @param priorityPaths 大 diff 裁剪时优先保留的路径 glob（04§3.1）
 * @param skipPaths 完全跳过的路径 glob（04§3.1）
 * @param failureCriteria What counts as failure 段（硬性失败标准）
 * @param evidenceRequirements Evidence requirements 段（证据要求覆写）
 * @param conventions Conventions 段（代码约定清单）
 * @param productContext Product context 段（v1.3：产品背景与隐含假设，CrossChecker 模式 A 消费）
 * @param knownIncidents Known incidents 段（v1.3：历史事故/复盘条目，CrossChecker 矛盾检索来源）
 * @param sniffTests Sniff tests 段（v1.3：经验性嗅探检查，CrossChecker 模式 B 消费）
 */
public record ReviewPolicy(
    boolean present,
    List<String> warnings,
    Integer nitBudget,
    List<String> priorityPaths,
    List<String> skipPaths,
    List<String> failureCriteria,
    List<String> evidenceRequirements,
    List<String> conventions,
    List<String> productContext,
    List<String> knownIncidents,
    List<String> sniffTests) {

  public ReviewPolicy {
    warnings = List.copyOf(warnings == null ? List.of() : warnings);
    priorityPaths = List.copyOf(priorityPaths == null ? List.of() : priorityPaths);
    skipPaths = List.copyOf(skipPaths == null ? List.of() : skipPaths);
    failureCriteria = List.copyOf(failureCriteria == null ? List.of() : failureCriteria);
    evidenceRequirements =
        List.copyOf(evidenceRequirements == null ? List.of() : evidenceRequirements);
    conventions = List.copyOf(conventions == null ? List.of() : conventions);
    productContext = List.copyOf(productContext == null ? List.of() : productContext);
    knownIncidents = List.copyOf(knownIncidents == null ? List.of() : knownIncidents);
    sniffTests = List.copyOf(sniffTests == null ? List.of() : sniffTests);
  }

  /**
   * 最小默认策略（REVIEW.md 缺失或解析失败时，04§3.2 规则 4）。
   *
   * @return 全空段落的默认策略
   */
  public static ReviewPolicy empty() {
    return new ReviewPolicy(
        false, List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of());
  }

  /**
   * 生效的 nit 预算。
   *
   * @param defaultValue 策略未配置时的部署默认值
   * @return REVIEW.md 配置值优先，否则默认值
   */
  public int effectiveNitBudget(int defaultValue) {
    return Optional.ofNullable(nitBudget).orElse(defaultValue);
  }
}
