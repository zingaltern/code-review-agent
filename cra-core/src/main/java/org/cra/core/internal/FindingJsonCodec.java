package org.cra.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;

/**
 * LLM JSON 输出 → CandidateFinding（04§6 结构化输出要点：解析失败携校验错误交由上层重试 ≤2， 仍失败丢弃记 SCHEMA_VIOLATION；禁止重试时放宽
 * schema、禁止正则修 JSON）。
 */
final class FindingJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final SplittableRandom RANDOM = new SplittableRandom();

  private FindingJsonCodec() {}

  /** 解析结果：合法 finding + 逐条校验错误（04§6 携错误重试的载体）。 */
  record ParseResult(List<CandidateFinding> findings, List<String> errors) {}

  /**
   * 解析输出契约的 {"findings": [...]}。
   *
   * @param json LLM 回复内容
   * @param runId run 标识（finding id 由系统生成，05§1.1）
   * @param agentRole 产出角色（origin.agent_role）
   * @param model 产出模型（origin.model）
   * @return 解析结果
   */
  static ParseResult parse(String json, String runId, String agentRole, String model) {
    List<CandidateFinding> findings = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new ParseResult(List.of(), List.of("root: 非合法 JSON: " + e.getOriginalMessage()));
    }
    JsonNode array = root.path("findings");
    if (!array.isArray()) {
      return new ParseResult(List.of(), List.of("root: 缺 findings 数组"));
    }
    int index = 0;
    for (JsonNode node : array) {
      try {
        findings.add(toFinding(node, runId, agentRole, model));
      } catch (IllegalArgumentException | IllegalStateException e) {
        errors.add("finding[" + index + "]: " + e.getMessage());
      }
      index++;
    }
    return new ParseResult(List.copyOf(findings), List.copyOf(errors));
  }

  private static CandidateFinding toFinding(
      JsonNode node, String runId, String agentRole, String model) {
    String category = requireText(node, "category");
    // 枚举拒收是行为（05§1.1 含 context）：未知类别由 valueOf 抛出 → 记 schema violation
    FindingCategory categoryEnum = FindingCategory.valueOf(category.toUpperCase().strip());
    Severity severity =
        Severity.valueOf(requireText(node, "severity_proposed").toUpperCase().strip());
    Side side =
        node.hasNonNull("side")
            ? Side.valueOf(node.get("side").asText().toUpperCase().strip())
            : Side.RIGHT;
    Double confidence = node.hasNonNull("confidence") ? node.get("confidence").asDouble() : null;
    return new CandidateFinding(
        "f_" + randomHex(),
        runId,
        categoryEnum,
        requireText(node, "title"),
        requireText(node, "description"),
        requireText(node, "failure_mode"),
        requireText(node, "file"),
        node.path("line").asInt(0),
        side,
        requireText(node, "evidence"),
        node.hasNonNull("suggestion") ? node.get("suggestion").asText() : null,
        severity,
        node.path("pre_existing_suspect").asBoolean(false),
        confidence,
        agentRole,
        model);
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalStateException("缺字段或为空: " + field);
    }
    return value.asText();
  }

  private static String randomHex() {
    return String.format("%08x%04x", RANDOM.nextInt(), RANDOM.nextInt(1 << 16));
  }
}
