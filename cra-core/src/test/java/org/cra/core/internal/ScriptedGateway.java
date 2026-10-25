package org.cra.core.internal;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;

/**
 * 脚本化 LLM 网关测试夹具（08§3.1：LLM 调用不进单测——以 caller 字段路由脚本响应，评审波回放 JSON， 验证波默认对 brief 中出现的全部 finding id 回
 * CONFIRMED（rationale 含新引用 L3 → 反锚定 PASS）， Judge 按脚本。录得各角色调用供断言。
 */
final class ScriptedGateway implements LlmGateway {

  private static final Pattern FINDING_ID = Pattern.compile("f_[0-9a-f]+");

  private final LinkedList<LlmResponse> review = new LinkedList<>();
  private final LinkedList<LlmResponse> crossChecker = new LinkedList<>();
  private final LinkedList<LlmResponse> verifier = new LinkedList<>();
  private final LinkedList<LlmResponse> judge = new LinkedList<>();
  // 评审/验证波并发调用网关：队列与记录全部走同一把锁（评审波 chunk 并发 8）
  private final Object lock = new Object();
  final List<LlmRequest> crossCheckerCalls = new ArrayList<>();
  final List<LlmRequest> verifierCalls = new ArrayList<>();
  final List<LlmRequest> judgeCalls = new ArrayList<>();
  final List<LlmRequest> reviewCalls = new ArrayList<>();
  volatile boolean verifierAutoConfirm = true;
  private volatile java.util.function.UnaryOperator<String> verdictScript;
  // auto-confirm 响应的 token 计量（预算阶梯用例需压小验证消耗，08§5 场景 11 的算术适配）
  volatile int autoTokensIn = 200;
  volatile int autoTokensOut = 60;

  ScriptedGateway review(String json) {
    review.add(LlmResponse.ok(json, 100, 20, 5, "glm-x"));
    return this;
  }

  /** 指定 token 计量的评审响应（预算阶梯用例的算术载体，08§5 场景 11）。 */
  ScriptedGateway review(String json, int tokensIn, int tokensOut) {
    review.add(LlmResponse.ok(json, tokensIn, tokensOut, 5, "glm-x"));
    return this;
  }

  /** CrossChecker 脚本响应（M4f-lite：context 类 finding）。 */
  ScriptedGateway crossChecker(String json) {
    crossChecker.add(LlmResponse.ok(json, 900, 120, 5, "glm-x"));
    return this;
  }

  ScriptedGateway reviewUnavailable() {
    review.add(LlmResponse.unavailable("retries exhausted", 0, 10));
    return this;
  }

  ScriptedGateway verifier(LlmResponse... responses) {
    for (LlmResponse response : responses) {
      verifier.add(response);
    }
    return this;
  }

  /** 全部推翻（REFUTED）脚本（按 brief 内每个 finding id 生成）。 */
  ScriptedGateway verifierRefuteAll() {
    return verifierScript(
        brief -> {
          StringBuilder sb = new StringBuilder("{\"verdicts\":[");
          java.util.regex.Matcher matcher =
              java.util.regex.Pattern.compile("f_[0-9a-f]+").matcher(brief);
          java.util.List<String> ids = new java.util.ArrayList<>();
          while (matcher.find()) {
            if (!ids.contains(matcher.group())) {
              ids.add(matcher.group());
            }
          }
          for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
              sb.append(",");
            }
            sb.append("{\"finding_id\":\"")
                .append(ids.get(i))
                .append("\",\"verdict\":\"REFUTED\",\"rationale\":\"L3 已有防护，触发条件不可达\"}");
          }
          sb.append("]}");
          return sb.toString();
        });
  }

  /** verifier 不可得（重试耗尽后 UNVERIFIED 场景）。 */
  ScriptedGateway verifierUnavailable() {
    verifierAutoConfirm = false;
    return this;
  }

  ScriptedGateway judge(String verdictJson) {
    judge.add(LlmResponse.ok(verdictJson, 80, 15, 5, "glm-x"));
    return this;
  }

  ScriptedGateway judgeUnavailable() {
    judge.add(LlmResponse.unavailable("judge down", 0, 10));
    return this;
  }

  /** 脚本化 verifier 响应（verdicts 形态）。 */
  ScriptedGateway verifier(String verdictsJson) {
    verifier.add(LlmResponse.ok(verdictsJson, 200, 60, 5, "glm-x"));
    return this;
  }

  /**
   * 按 brief 生成 verdicts 的脚本（finding id 由 codec 随机生成，测试不可预知——按 brief 内容响应）。 入参 = verifier
   * userPrompt，出参 = verdicts JSON；优先于显式队列与 auto-confirm。
   */
  ScriptedGateway verifierScript(java.util.function.UnaryOperator<String> script) {
    this.verdictScript = script;
    return this;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    synchronized (lock) {
      return dispatch(request);
    }
  }

  private LlmResponse dispatch(LlmRequest request) {
    switch (request.caller()) {
      case "general-reviewer",
          "bug-hunter/boundary",
          "bug-hunter/dataflow",
          "bug-hunter/concurrency",
          "bug-hunter/resource",
          "security-reviewer",
          "test-reviewer",
          "convention-reviewer",
          "perf-reviewer" -> {
        reviewCalls.add(request);
        return pollOrRepeat(review);
      }
      case "cross-checker" -> {
        crossCheckerCalls.add(request);
        return pollOrRepeat(crossChecker);
      }
      case "verifier" -> {
        verifierCalls.add(request);
        if (verdictScript != null) {
          return LlmResponse.ok(
              verdictScript.apply(request.userPrompt()), autoTokensIn, autoTokensOut, 5, "glm-x");
        }
        if (!verifier.isEmpty()) {
          return verifier.poll();
        }
        if (verifierAutoConfirm) {
          return LlmResponse.ok(
              confirmAll(request.userPrompt()), autoTokensIn, autoTokensOut, 5, "glm-x");
        }
        return LlmResponse.unavailable("verifier down (scripted)", 0, 10);
      }
      case "judge" -> {
        judgeCalls.add(request);
        return pollOrUnavailable(judge);
      }
      default -> {
        throw new IllegalStateException("未知 caller: " + request.caller());
      }
    }
  }

  int totalCalls() {
    synchronized (lock) {
      return reviewCalls.size()
          + crossCheckerCalls.size()
          + verifierCalls.size()
          + judgeCalls.size();
    }
  }

  /** 评审队列耗尽时重复最后一条——对齐 M0 mock 的单值 thenReturn 无限语义（多 chunk 共用一份脚本）。 */
  private static LlmResponse pollOrRepeat(LinkedList<LlmResponse> queue) {
    return queue.isEmpty()
        ? LlmResponse.unavailable("script exhausted", 0, 10)
        : queue.size() == 1 ? queue.peek() : queue.poll();
  }

  /** 脚本队列耗尽即不可得（judge：未脚本化 = 不可得，不做无限重复）。 */
  private static LlmResponse pollOrUnavailable(LinkedList<LlmResponse> queue) {
    return queue.isEmpty() ? LlmResponse.unavailable("script exhausted", 0, 10) : queue.poll();
  }

  /** 对 brief 中出现的全部 finding id 回 CONFIRMED（rationale 引用独立内容——反锚定 PASS）。 */
  static String confirmAll(String userPrompt) {
    StringBuilder sb = new StringBuilder("{\"verdicts\":[");
    Matcher matcher = FINDING_ID.matcher(userPrompt);
    List<String> ids = new ArrayList<>();
    while (matcher.find()) {
      if (!ids.contains(matcher.group())) {
        ids.add(matcher.group());
      }
    }
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"finding_id\":\"")
          .append(ids.get(i))
          .append("\",\"verdict\":\"CONFIRMED\",\"rationale\":\"")
          .append(
              "独立复核：重建了证据链，失败模式重述：集合为空时按索引取元素必然抛运行时异常。\\n```\\nassertTrue(list.isEmpty())\\n```")
          .append("\"}");
    }
    sb.append("]}");
    return sb.toString();
  }
}
