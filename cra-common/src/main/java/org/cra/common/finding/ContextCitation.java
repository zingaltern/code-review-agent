package org.cra.common.finding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * context 类 finding 的矛盾来源引用格式（03§3.1 / 03§4.2 v1.3.1 的权威定义，M4f 落定）。
 *
 * <p>CrossChecker 产出的 {@code category=context} finding 必须在 {@code evidence} 中携带可机器解析的矛盾来源，
 * 验证层据此预取被引证据（{@code CitedEvidenceResolver} → verifier/judge brief 分型组装，03§4.2）。格式（每行一条，置于 evidence
 * 末尾）：
 *
 * <pre>
 * 矛盾来源：
 * - commit &lt;7-40 位小写 hex sha&gt; path &lt;该 commit 内文件路径&gt; — 说明（可选）
 * - REVIEW.md Known incidents: &lt;条目原文或前缀&gt;
 * - REVIEW.md Product context: &lt;条目原文或前缀&gt;
 * - REVIEW.md Sniff tests: &lt;条目原文或前缀&gt;
 * </pre>
 *
 * <p>解析规则（本类即唯一实现，跨模块共用）：
 *
 * <ol>
 *   <li>结构化 commit 引用优先：{@code commit <sha> path <path>}（path 可省，省略时调用方以 finding.file 兜底）；
 *   <li>无结构化引用时退回裸 hex 引用（M2 兼容语义：{@code \b[0-9a-f]{7,40}\b}，每处按 finding.file 解析）；
 *   <li>REVIEW.md 条目锚：{@code REVIEW.md <段名>: <条目>}，段名 ∈ Known incidents / Product context / Sniff
 *       tests； 条目省略 = 整段（验证层注入整段条目）。
 * </ol>
 */
public final class ContextCitation {

  /** 段名规范化值（REVIEW.md 解析器与验证层组装共用）。 */
  public static final String PRODUCT_CONTEXT = "Product context";

  /** 已知事故段名。 */
  public static final String KNOWN_INCIDENTS = "Known incidents";

  /** 嗅探检查段名。 */
  public static final String SNIFF_TESTS = "Sniff tests";

  private static final Pattern COMMIT_REF =
      Pattern.compile(
          "(?i)\\bcommit\\s*[:：=]?\\s*([0-9a-f]{7,40})"
              + "(?:\\s+(?:path|file)\\s*[:：=]?\\s*([^\\s,;，；()（）\\[\\]]+))?");

  private static final Pattern BARE_SHA = Pattern.compile("\\b[0-9a-f]{7,40}\\b");

  private static final Pattern REVIEW_MD_REF =
      Pattern.compile(
          "(?i)REVIEW\\.md\\s*(?:§|#)?\\s*(known incidents|product context|sniff tests)"
              + "(?:\\s*[:：]\\s*([^\\n]*))?");

  private ContextCitation() {}

  /** 被引 commit（path 为 null = 未注明，调用方以 finding.file 兜底）。 */
  public record CommitRef(String sha, String path) {

    /**
     * 生效路径。
     *
     * @param defaultPath 引用未注明 path 时的兜底路径（finding.file）
     * @return 文件路径
     */
    public String effectivePath(String defaultPath) {
      return path == null || path.isBlank() ? defaultPath : path;
    }
  }

  /** 被引 REVIEW.md 条目（entry 为空 = 整段）。 */
  public record ReviewMdRef(String section, String entry) {

    /**
     * 规范化段名（Product context / Known incidents / Sniff tests）。
     *
     * @param raw 解析出的段名
     * @return 规范段名
     */
    public static String canonicalSection(String raw) {
      String lower = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
      return switch (lower) {
        case "known incidents" -> KNOWN_INCIDENTS;
        case "sniff tests" -> SNIFF_TESTS;
        default -> PRODUCT_CONTEXT;
      };
    }
  }

  /**
   * 解析 commit 引用（结构化优先，裸 hex 兜底；去重保序）。
   *
   * @param text 待解析文本（finding.evidence，必要时拼上 description）
   * @param defaultPath 裸 hex 引用的兜底路径（finding.file）
   * @return 被引 commit 列表
   */
  public static List<CommitRef> commitRefs(String text, String defaultPath) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<CommitRef> structured = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = COMMIT_REF.matcher(text);
    while (matcher.find()) {
      String sha = matcher.group(1).toLowerCase(Locale.ROOT);
      String path = matcher.group(2) == null ? null : stripTrailingPunctuation(matcher.group(2));
      if (seen.add(sha + "|" + (path == null ? "" : path))) {
        structured.add(new CommitRef(sha, path));
      }
    }
    if (!structured.isEmpty()) {
      return List.copyOf(structured);
    }
    // M2 兼容语义（03§4.2）：无结构化引用时裸 hex 亦可解析——path 以 finding.file 兜底
    List<CommitRef> bare = new ArrayList<>();
    Matcher bareMatcher = BARE_SHA.matcher(text);
    while (bareMatcher.find()) {
      String sha = bareMatcher.group().toLowerCase(Locale.ROOT);
      if (seen.add(sha + "|")) {
        bare.add(new CommitRef(sha, defaultPath));
      }
    }
    return List.copyOf(bare);
  }

  /**
   * 解析 REVIEW.md 条目锚（段名规范化；条目为空 = 整段）。
   *
   * @param text 待解析文本
   * @return 被引条目列表（去重保序）
   */
  public static List<ReviewMdRef> reviewMdRefs(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<ReviewMdRef> refs = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = REVIEW_MD_REF.matcher(text);
    while (matcher.find()) {
      String section = ReviewMdRef.canonicalSection(matcher.group(1));
      String entry = matcher.group(2) == null ? "" : matcher.group(2).strip();
      if (seen.add(section + "|" + entry)) {
        refs.add(new ReviewMdRef(section, entry));
      }
    }
    return List.copyOf(refs);
  }

  private static String stripTrailingPunctuation(String path) {
    String out = path;
    while (!out.isEmpty() && ".,;:，。；：".indexOf(out.charAt(out.length() - 1)) >= 0) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }
}
