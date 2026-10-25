package org.cra.common.finding;

import java.util.Objects;

/**
 * 候选发现（05§1.1，M0 子集——验证/锚定/指纹等生命周期字段由编排层状态表持有，不回写实体，09§6）。 构造即 schema 校验：非法输入抛 {@link
 * IllegalArgumentException}（04§6：解析失败携校验错误重试后丢弃）。
 *
 * @param id finding 标识（f_ 前缀）
 * @param runId 所属 run 标识（run_ 前缀）
 * @param category 类别，必为 {@link FindingCategory} 之一（含 context，05§1.1 v1.3.1）
 * @param title 一句话标题
 * @param description 问题描述（含发现者论证）
 * @param failureMode 触发条件→错误结果
 * @param file 相对仓库根的文件路径
 * @param line finding 行区间首行（锚行选取规则见 02§4.1）
 * @param side 行号所属侧，null 归一为 RIGHT
 * @param evidence 行级证据（铁律：必须引用行号，03§3）
 * @param suggestion 修复建议，可空
 * @param severityProposed 提议严重度（important|nit）
 * @param preExistingSuspect 疑似变更前已存在（由编排器 jgit blame 复核，05§2）
 * @param confidence 发现者自报置信度（不参与晋升，05§1.1），可空；存在时 [0,1]
 * @param originAgentRole 产出角色标识（kebab-case，如 general-reviewer）
 * @param originModel 产出模型名
 */
public record CandidateFinding(
    String id,
    String runId,
    FindingCategory category,
    String title,
    String description,
    String failureMode,
    String file,
    int line,
    Side side,
    String evidence,
    String suggestion,
    Severity severityProposed,
    boolean preExistingSuspect,
    Double confidence,
    String originAgentRole,
    String originModel) {

  public CandidateFinding {
    Objects.requireNonNull(id, "id");
    if (!id.startsWith("f_")) {
      throw new IllegalArgumentException("bad id: " + id);
    }
    Objects.requireNonNull(runId, "runId");
    if (!runId.startsWith("run_")) {
      throw new IllegalArgumentException("bad runId: " + runId);
    }
    Objects.requireNonNull(category, "category");
    requireNonBlank(title, "title");
    requireNonBlank(description, "description");
    requireNonBlank(failureMode, "failureMode");
    requireNonBlank(file, "file");
    if (line <= 0) {
      throw new IllegalArgumentException("line must be positive: " + line);
    }
    side = side == null ? Side.RIGHT : side;
    requireNonBlank(evidence, "evidence");
    Objects.requireNonNull(severityProposed, "severityProposed");
    if (confidence != null && (confidence < 0 || confidence > 1)) {
      throw new IllegalArgumentException("confidence must be in [0,1]: " + confidence);
    }
    requireNonBlank(originAgentRole, "originAgentRole");
    requireNonBlank(originModel, "originModel");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
