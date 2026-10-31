package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.run.ReviewRequest;
import org.cra.common.signoff.RiskTier;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffContext;
import org.cra.common.signoff.SignoffRuleHit;
import org.cra.common.signoff.TestsStatus;
import org.cra.core.api.SignoffOptions;

/**
 * 风险分级与签字建议生成（07§5 M4a；01§3.4 分级表）：纯确定性逻辑，不调用 LLM。
 *
 * <p>分级规则（可解释——每条命中都进 {@link SignoffRuleHit}）：
 *
 * <ul>
 *   <li><b>高（2 人）</b>：命中核心高危路径（安全/支付/鉴权）或难恢复的关键配置（部署/迁移/生产配置）；
 *   <li><b>中（1 人）</b>：常规业务逻辑、系统提示词、REVIEW.md 策略变更；
 *   <li><b>低（0 人）</b>：纯文档或仅功能开关变更，<strong>且</strong>测试状态 passed + 作者可信——任一条件不满足自动升为中风险。
 * </ul>
 *
 * <p>doc 文件不参与高危路径匹配（`docs/security/**` 仍是文档）；命中多条规则时取最高等级。输出绑定 {@link
 * ReviewRequest#headSha()}（approve 只对该 commit 有效，05§1.3 同源语义）。
 */
public final class SignoffAdvisor {

  /** 功能开关行判定：键名含 flag/toggle/feature/enabled，或布尔型 key: true/false。 */
  private static final Pattern FLAG_LINE =
      Pattern.compile(
          "(?i)(?:^|[._-])(?:feature|flags?|toggle|enabled|disabled)(?:[._-]|\\s*[:=])"
              + "|^[a-z0-9_.-]+\\s*:\\s*(?:true|false)$");

  private static final int RULE_VERSION = 1;

  private final SignoffOptions options;

  /**
   * @param options 签字配置（null 用默认）
   */
  public SignoffAdvisor(SignoffOptions options) {
    this.options = options == null ? SignoffOptions.defaults() : options;
  }

  /**
   * 生成签字建议。
   *
   * @param request 评审请求（提供 head sha 与作者/测试上下文）
   * @param diff 本次评审的变更集（全量 PR diff）
   * @return 签字建议（配置关闭时为 disabled）
   */
  public SignoffAdvice assess(ReviewRequest request, DiffResult diff) {
    SignoffContext context = request.signoff();
    if (!options.enabled()) {
      return SignoffAdvice.disabled(request.headSha(), context);
    }
    if (diff == null || diff.files().isEmpty()) {
      return SignoffAdvice.advisory(
          RiskTier.LOW,
          request.headSha(),
          context,
          List.of(new SignoffRuleHit("no-change", RiskTier.LOW, "无变更文件，无需签字", List.of())));
    }

    Map<String, RuleAccumulator> hits = new LinkedHashMap<>();
    boolean allDocs = true;
    boolean allFlagOnly = true;
    List<String> allPaths = new ArrayList<>();
    for (DiffFile file : diff.files()) {
      List<String> paths = effectivePaths(file);
      allPaths.addAll(paths);
      boolean doc = paths.stream().allMatch(this::isDoc);
      allDocs &= doc;
      boolean flagOnly = flagOnly(file) && paths.stream().allMatch(this::isFeatureFlagPath);
      allFlagOnly &= flagOnly;
      if (!doc) {
        // 高危规则优先于其余分类（同一文件命中多条时取最高等级）
        List<String> highRisk = filterMatches(paths, options.highRiskPaths());
        if (!highRisk.isEmpty()) {
          add(hits, "high-risk-path", RiskTier.HIGH, "命中核心高危路径（安全/支付/鉴权区域）", highRisk);
          continue;
        }
        List<String> critical = filterMatches(paths, options.criticalConfigPaths());
        if (!critical.isEmpty()) {
          add(hits, "critical-config-path", RiskTier.HIGH, "命中难恢复的关键配置（部署/迁移/生产配置）", critical);
          continue;
        }
      }
      if (paths.stream().anyMatch(this::isReviewPolicy)) {
        add(hits, "review-policy-change", RiskTier.MEDIUM, "REVIEW.md 评审策略变更", paths);
        continue;
      }
      if (paths.stream().anyMatch(this::isSystemPrompt)) {
        add(hits, "system-prompt", RiskTier.MEDIUM, "系统提示词变更", paths);
        continue;
      }
      if (doc) {
        continue;
      }
      if (flagOnly) {
        continue;
      }
      add(hits, "business-logic", RiskTier.MEDIUM, "常规业务逻辑变更", paths);
    }

    boolean lowCandidate = false;
    if (hits.isEmpty() && allDocs) {
      lowCandidate = true;
      add(hits, "docs-only", RiskTier.LOW, "纯文档变更", allPaths);
    }
    if (hits.isEmpty() && allFlagOnly) {
      lowCandidate = true;
      add(hits, "feature-flag-only", RiskTier.LOW, "仅功能开关变更", allPaths);
    }
    if (lowCandidate) {
      boolean testsPassed = context.testsStatus() == TestsStatus.PASSED;
      boolean trustedAuthor = context.authorKnown() && options.isTrustedAuthor(context.author());
      if (!testsPassed) {
        add(
            hits,
            "low-risk-gate-tests",
            RiskTier.MEDIUM,
            "低风险前置条件未满足：测试状态 "
                + context.testsStatus().name().toLowerCase(Locale.ROOT)
                + "（需 passed）",
            List.of());
      }
      if (!trustedAuthor) {
        add(
            hits,
            "low-risk-gate-author",
            RiskTier.MEDIUM,
            "低风险前置条件未满足：作者 "
                + (context.authorKnown() ? context.author() : "未知")
                + " 不在 trusted-authors",
            List.of(context.authorKnown() ? "author=" + context.author() : "author=unknown"));
      }
      if (testsPassed && trustedAuthor) {
        add(
            hits,
            "low-risk-conditions-met",
            RiskTier.LOW,
            "测试通过且作者可信（0 人签前提满足）",
            List.of("author=" + context.author()));
      }
    }
    if (hits.isEmpty()) {
      // 兜底：混合形态（如文档 + 开关配置各一）无法归入低风险，按常规变更处理
      add(hits, "unclassified-change", RiskTier.MEDIUM, "未能归入低/高风险的常规变更", allPaths);
    }

    RiskTier tier =
        hits.values().stream()
            .map(accumulator -> accumulator.tier)
            .reduce(RiskTier.LOW, RiskTier::max);
    List<SignoffRuleHit> rules =
        hits.values().stream()
            .map(RuleAccumulator::toHit)
            .sorted(
                Comparator.comparingInt((SignoffRuleHit hit) -> hit.tier().ordinal())
                    .reversed()
                    .thenComparing(SignoffRuleHit::id))
            .toList();
    return SignoffAdvice.advisory(tier, request.headSha(), context, rules);
  }

  /** 规则版本（trace/报告留痕；规则语义变更时递增）。 */
  public int ruleVersion() {
    return RULE_VERSION;
  }

  private static List<String> effectivePaths(DiffFile file) {
    List<String> paths = new ArrayList<>();
    paths.add(file.path());
    if (file.previousPath() != null
        && !file.previousPath().isBlank()
        && !file.previousPath().equals(file.path())) {
      paths.add(file.previousPath());
    }
    return paths;
  }

  private static List<String> filterMatches(List<String> paths, List<String> globs) {
    return paths.stream().filter(path -> matchesAny(globs, path)).distinct().toList();
  }

  private boolean isDoc(String path) {
    return matchesAny(options.docPaths(), path);
  }

  private boolean isReviewPolicy(String path) {
    return "REVIEW.md".equalsIgnoreCase(fileName(path));
  }

  private boolean isSystemPrompt(String path) {
    return matchesAny(options.systemPromptPaths(), path);
  }

  private boolean isFeatureFlagPath(String path) {
    return matchesAny(options.featureFlagPaths(), path);
  }

  /**
   * 路径 glob 匹配（带前导 / 的变体：`**&#47;auth&#47;**` 同时命中根级 `auth/Foo.java`）。
   *
   * @param globs 模式列表
   * @param path 相对路径
   * @return true 若命中
   */
  private static boolean matchesAny(List<String> globs, String path) {
    return PathGlobs.matchesAny(globs, path) || PathGlobs.matchesAny(globs, "/" + path);
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  /**
   * 变更行是否全部为功能开关行（新增/删除行；注释与空行忽略；至少有一行实际变更）。
   *
   * @param file 变更文件
   * @return true 若仅开关行
   */
  private static boolean flagOnly(DiffFile file) {
    String patch = file.patch();
    if (patch == null || patch.isBlank()) {
      return false;
    }
    boolean sawChange = false;
    for (String line : patch.split("\n")) {
      if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
        continue;
      }
      if (!line.startsWith("+") && !line.startsWith("-")) {
        continue;
      }
      String content = line.substring(1).strip();
      if (content.isEmpty() || content.startsWith("#") || content.startsWith("//")) {
        continue;
      }
      sawChange = true;
      if (!FLAG_LINE.matcher(content).find()) {
        return false;
      }
    }
    return sawChange;
  }

  private static void add(
      Map<String, RuleAccumulator> hits,
      String id,
      RiskTier tier,
      String reason,
      List<String> evidence) {
    RuleAccumulator accumulator =
        hits.computeIfAbsent(id, key -> new RuleAccumulator(id, tier, reason));
    accumulator.evidence.addAll(evidence);
  }

  /** 规则聚合（同规则多文件命中合并为一条，证据去重）。 */
  private static final class RuleAccumulator {
    private final String id;
    private final RiskTier tier;
    private final String reason;
    private final List<String> evidence = new ArrayList<>();

    private RuleAccumulator(String id, RiskTier tier, String reason) {
      this.id = id;
      this.tier = tier;
      this.reason = reason;
    }

    private SignoffRuleHit toHit() {
      return new SignoffRuleHit(id, tier, reason, evidence.stream().distinct().toList());
    }
  }
}
