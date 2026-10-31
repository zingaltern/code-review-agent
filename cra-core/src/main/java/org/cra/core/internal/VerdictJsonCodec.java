package org.cra.core.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VerifierAgent JSON 输出 → 判定表（04§6 结构化输出要点：解析错误交由上层携错重试 ≤2；禁止放宽 schema、 禁止正则修 JSON）。输出契约见
 * prompts/verifier/balanced-review.md：{"verdicts":[{finding_id, verdict, rationale, followup_file?,
 * refutation?}]}。
 *
 * <p>v1.4（ADR-0015）：bug 类 REFUTED 必须携带 {@code refutation} 对象（kind/anchor/quote）， 机械校验（{@link
 * RefuteEvidenceGate}）据此决定是否降级为 INSUFFICIENT_EVIDENCE。
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

  /** 反证类型（ADR-0015 §2.1）：只有前两类 + CONTRACT_SAFE 构成"对失败模式的否定"； CITATION_MISMATCH 仅表示结论引述不准，不构成推翻。 */
  public enum RefutationKind {
    /** 触发条件不可达 */
    UNREACHABLE,
    /** 上游/同一方法已有防护 */
    GUARD_EXISTS,
    /** 契约保证输入不可能出现（如非 null 契约 + 受约束调用点） */
    CONTRACT_SAFE,
    /** 结论的方法名/行号/引述与实际变更不符（不是对失败模式的否定） */
    CITATION_MISMATCH
  }

  /**
   * 结构化反证（ADR-0015）。
   *
   * @param kind 反证类型（未知值按缺失处理）
   * @param anchor 反证位置（path:line，自由格式但须非空）
   * @param quote 简报中逐字出现的代码片段（机械校验用）
   * @param reason 反证理由
   * @param malformed 解析期是否发现结构不合法（非对象/字段缺失/kind 非法）——按缺失处理
   */
  public record Refutation(
      RefutationKind kind, String anchor, String quote, String reason, boolean malformed) {

    /** 结构化反证是否可用于 REFUTED 判定（字段齐全且 kind 已知）。 */
    public boolean usable() {
      return !malformed && kind != null && anchor != null && !anchor.isBlank();
    }
  }

  /** 单条判定（anti_anchor_check 由校验器另行产出，不在本表）。 */
  public record ParsedVerdict(
      VerifierVerdict verdict, String rationale, String followupFile, Refutation refutation) {}

  /** 解析结果：finding_id → 判定 + 错误列表（携错重试载体）。 */
  public record ParseResult(
      Map<String, ParsedVerdict> verdicts, Map<String, String> followups, List<String> errors) {

    public ParseResult {
      verdicts =
          verdicts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(verdicts));
      followups =
          followups == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(followups));
      errors = List.copyOf(errors == null ? List.of() : errors);
    }
  }

  private VerdictJsonCodec() {}

  /**
   * 解析验证批次输出。缺 rationale 判错（契约要求判定理由）；verdict 非法枚举判错——两者都触发上层重试。 {@code refutation}
   * 结构不合法不判错（兼容旧输出；由 {@link RefuteEvidenceGate} 按缺失降级）， 以免整批判定因一个附加字段不可用。
   *
   * @param json LLM 回复内容
   * @return 解析结果（errors 非空时不消费 verdicts）
   */
  public static ParseResult parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      return new ParseResult(
          Map.of(), Map.of(), List.of("root: 非合法 JSON: " + e.getOriginalMessage()));
    }
    JsonNode array = root.path("verdicts");
    if (!array.isArray() || array.isEmpty()) {
      return new ParseResult(Map.of(), Map.of(), List.of("root: 缺非空 verdicts 数组"));
    }
    Map<String, ParsedVerdict> verdicts = new LinkedHashMap<>();
    Map<String, String> followups = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();
    int index = 0;
    for (JsonNode node : array) {
      applyVerdict(node, index, verdicts, followups, errors);
      index++;
    }
    return new ParseResult(verdicts, followups, errors);
  }

  /**
   * 解析单条 verdict 并登记（非法即记错误，不阻断整批）。
   *
   * @param node verdict 节点
   * @param index 数组下标（错误信息用）
   * @param verdicts 判定输出
   * @param followups followup_file 输出
   * @param errors 错误列表
   */
  private static void applyVerdict(
      JsonNode node,
      int index,
      Map<String, ParsedVerdict> verdicts,
      Map<String, String> followups,
      List<String> errors) {
    String findingId = textOf(node, "finding_id");
    String verdict = textOf(node, "verdict");
    String rationale = textOf(node, "rationale");
    if (findingId == null || findingId.isBlank()) {
      // 空串不是合法 id：放行会让整批判定按真实 id 查不到，静默退化为 UNAVAILABLE
      errors.add("verdicts[" + index + "]: 缺 finding_id");
      return;
    }
    if (verdict == null) {
      errors.add("verdicts[" + index + "]: 缺 verdict");
      return;
    }
    if (rationale == null || rationale.isBlank()) {
      errors.add("verdicts[" + index + "]: 缺 rationale（判定要求附理由）");
      return;
    }
    try {
      VerifierVerdict parsed = VerifierVerdict.valueOf(verdict.toUpperCase().strip());
      if (parsed == VerifierVerdict.UNAVAILABLE) {
        errors.add("verdicts[" + index + "]: UNAVAILABLE 是编排器语义值，不是合法输出");
      } else if (verdicts.containsKey(findingId)) {
        errors.add("verdicts[" + index + "]: finding_id 重复: " + findingId);
      } else {
        verdicts.put(
            findingId,
            new ParsedVerdict(
                parsed,
                rationale,
                textOf(node, "followup_file"),
                parseRefutation(node.get("refutation"))));
        if (node.hasNonNull("followup_file")) {
          followups.put(findingId, node.get("followup_file").asText());
        }
      }
    } catch (IllegalArgumentException e) {
      errors.add("verdicts[" + index + "]: 非法 verdict: " + verdict);
    }
  }

  /** 解析 refutation 对象；结构不合法返回 malformed 标记（不判错，交机械校验降级）。 */
  private static Refutation parseRefutation(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      return new Refutation(null, null, null, null, true);
    }
    String kindText = textOf(node, "kind");
    String anchor = textOf(node, "anchor");
    String quote = textOf(node, "quote");
    String reason = textOf(node, "reason");
    RefutationKind kind = null;
    boolean malformed = false;
    if (kindText != null) {
      try {
        kind = RefutationKind.valueOf(kindText.toUpperCase().strip());
      } catch (IllegalArgumentException e) {
        malformed = true;
      }
    }
    if (quote == null || quote.isBlank()) {
      malformed = true;
    }
    return new Refutation(kind, anchor, quote, reason, malformed);
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
