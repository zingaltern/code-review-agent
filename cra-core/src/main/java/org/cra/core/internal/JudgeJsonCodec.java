package org.cra.core.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * JudgeAgent JSON 输出 → 终裁判定（04§6；输出契约见 prompts/judge/arbitrate.md：
 * {"judgement":{"verdict":"CONFIRMED"|"REJECTED","rationale":"…"}}——不折中、不求共识，无第三态）。
 */
public final class JudgeJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 终裁二值。 */
  public enum JudgeVerdict {
    CONFIRMED,
    REJECTED
  }

  /** 终裁结果。 */
  public record Judgement(JudgeVerdict verdict, String rationale) {}

  private JudgeJsonCodec() {}

  /**
   * 解析终裁输出。
   *
   * @param json LLM 回复内容
   * @return 判定或错误（errors 非空时上层携错重试）
   */
  public static ParseResult parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      return new ParseResult(null, List.of("root: 非合法 JSON: " + e.getOriginalMessage()));
    }
    JsonNode judgement = root.path("judgement");
    if (!judgement.isObject()) {
      return new ParseResult(null, List.of("root: 缺 judgement 对象"));
    }
    JsonNode verdict = judgement.get("verdict");
    JsonNode rationale = judgement.get("rationale");
    if (verdict == null || verdict.isNull() || verdict.asText().isBlank()) {
      return new ParseResult(null, List.of("judgement: 缺 verdict"));
    }
    if (rationale == null || rationale.isNull() || rationale.asText().isBlank()) {
      return new ParseResult(null, List.of("judgement: 缺 rationale"));
    }
    try {
      JudgeVerdict parsed = JudgeVerdict.valueOf(verdict.asText().toUpperCase().strip());
      return new ParseResult(new Judgement(parsed, rationale.asText()), List.of());
    } catch (IllegalArgumentException e) {
      return new ParseResult(
          null, List.of("judgement: 非法 verdict（仅 CONFIRMED|REJECTED）: " + verdict.asText()));
    }
  }

  /** 解析结果载体（与 {@link AgentRunner.FinalOutputParser.Parsed} 对接）。 */
  public record ParseResult(Judgement judgement, List<String> errors) {}
}
