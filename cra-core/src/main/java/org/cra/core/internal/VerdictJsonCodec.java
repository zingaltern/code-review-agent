package org.cra.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VerifierAgent JSON 输出 → 判定表（04§6 结构化输出要点：解析错误交由上层携错重试 ≤2；禁止放宽 schema、 禁止正则修 JSON）。输出契约见
 * prompts/verifier/balanced-review.md：{"verdicts":[{finding_id, verdict, rationale,
 * followup_file?}]}。
 */
public final class VerdictJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 验证者三值判定 + 编排器语义值 UNAVAILABLE（验证不可得，02§4.2；不来自 JSON）。 */
  public enum VerifierVerdict {
    CONFIRMED,
    REFUTED,
    INSUFFICIENT_EVIDENCE,
    UNAVAILABLE
  }

  /** 单条判定（anti_anchor_check 由校验器另行产出，不在本表）。 */
  public record ParsedVerdict(VerifierVerdict verdict, String rationale, String followupFile) {}

  /** 解析结果：finding_id → 判定 + 错误列表（携错重试载体）。 */
  public record ParseResult(
      Map<String, ParsedVerdict> verdicts,
      Map<String, String> followups,
      java.util.List<String> errors) {

    public ParseResult {
      verdicts =
          verdicts == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(verdicts));
      followups =
          followups == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(followups));
      errors = java.util.List.copyOf(errors == null ? java.util.List.of() : errors);
    }
  }

  private VerdictJsonCodec() {}

  /**
   * 解析验证批次输出。缺 rationale 判错（契约要求判定理由）；verdict 非法枚举判错——两者都触发上层重试。
   *
   * @param json LLM 回复内容
   * @return 解析结果（errors 非空时不消费 verdicts）
   */
  public static ParseResult parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new ParseResult(
          Map.of(), Map.of(), java.util.List.of("root: 非合法 JSON: " + e.getOriginalMessage()));
    }
    JsonNode array = root.path("verdicts");
    if (!array.isArray() || array.isEmpty()) {
      return new ParseResult(Map.of(), Map.of(), java.util.List.of("root: 缺非空 verdicts 数组"));
    }
    Map<String, ParsedVerdict> verdicts = new LinkedHashMap<>();
    Map<String, String> followups = new LinkedHashMap<>();
    java.util.List<String> errors = new java.util.ArrayList<>();
    int index = 0;
    for (JsonNode node : array) {
      String findingId = textOf(node, "finding_id");
      String verdict = textOf(node, "verdict");
      String rationale = textOf(node, "rationale");
      if (findingId == null) {
        errors.add("verdicts[" + index + "]: 缺 finding_id");
      } else if (verdict == null) {
        errors.add("verdicts[" + index + "]: 缺 verdict");
      } else if (rationale == null || rationale.isBlank()) {
        errors.add("verdicts[" + index + "]: 缺 rationale（判定要求附理由）");
      } else {
        try {
          VerifierVerdict parsed = VerifierVerdict.valueOf(verdict.toUpperCase().strip());
          if (parsed == VerifierVerdict.UNAVAILABLE) {
            errors.add("verdicts[" + index + "]: UNAVAILABLE 是编排器语义值，不是合法输出");
          } else if (verdicts.containsKey(findingId)) {
            errors.add("verdicts[" + index + "]: finding_id 重复: " + findingId);
          } else {
            verdicts.put(
                findingId, new ParsedVerdict(parsed, rationale, textOf(node, "followup_file")));
            if (node.hasNonNull("followup_file")) {
              followups.put(findingId, node.get("followup_file").asText());
            }
          }
        } catch (IllegalArgumentException e) {
          errors.add("verdicts[" + index + "]: 非法 verdict: " + verdict);
        }
      }
      index++;
    }
    return new ParseResult(verdicts, followups, errors);
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
