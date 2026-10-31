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
 * - event &lt;event_id&gt; — 说明（可选；M4f-full，事件史条目）
 * - REVIEW.md Known incidents: &lt;条目原文或前缀&gt;
 * - REVIEW.md Product context: &lt;条目原文或前缀&gt;
 * - REVIEW.md Sniff tests: &lt;条目原文或前缀&gt;
 * </pre>
 *
 * <p>解析规则（本类即唯一实现，跨模块共用）：
 *
 * <ol>
 *   <li>结构化 commit 引用优先：{@code commit <sha> path <path>}（path 可省，省略时调用方以 finding.file 兜底；sha
 *       后仍为十六进制字符的超长串不命中，不截前 40 位——与规则 2 的 {@code \b} 语义对齐）；
 *   <li>无结构化引用时退回裸 hex 引用（M2 兼容语义：{@code \b[0-9a-f]{7,40}\b}，每处按 finding.file 解析）；
 *   <li>事件引用：{@code event <event_id>}（行首或列表项；M4f-full 事件史，验证层按事件 id 预取条目）；
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
          "(?i)\\bcommit\\s*[:：=]?\\s*([0-9a-f]{7,40})(?![0-9a-f])"
              + "(?:\\s+(?:path|file)\\s*[:：=]?\\s*([^\\s,;，；()（）\\[\\]]+))?");

  private static final Pattern BARE_SHA = Pattern.compile("\\b[0-9a-f]{7,40}\\b");

  /** 事件引用（行首或列表项；id 字符集与 ProductEvent.EVENT_ID_PATTERN 一致）。 */
  private static final Pattern EVENT_REF =
      Pattern.compile(
          "(?im)^\\s*(?:[-*]\\s*)?event\\s*[:：=]?\\s*([A-Za-z0-9][A-Za-z0-9._:#/-]{0,199})"
              + "(?![A-Za-z0-9._:#/-])");

  /** 矛盾来源块标题（事件引用只在块内解析，避免正文里 "event loop" 之类误命中）。 */
  private static final String CITATION_HEADER = "矛盾来源";

  private static final Pattern REVIEW_MD_REF =
      Pattern.compile(
          "(?i)REVIEW\\.md\\s*(?:§|#)?\\s*(known incidents|product context|sniff tests)"
              + "(?:\\s*[:：]\\s*([^\\n]*))?");

  private ContextCitation() {}

  /**
   * 被引 commit。
   *
   * @param sha 7-40 位小写 hex sha
   * @param path 该 commit 内文件路径；null = 未注明，调用方以 finding.file 兜底
   */
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

  /**
   * 被引事件史条目（ADR-0012：{@code event <event_id>}）。
   *
   * @param eventId 产品事件标识（与 ProductEvent.EVENT_ID_PATTERN 一致）
   */
  public record EventRef(String eventId) {}

  /**
   * 被引 REVIEW.md 条目（entry 为空 = 整段）。
   *
   * @param section 规范段名（Product context / Known incidents / Sniff tests）
   * @param entry 条目原文或前缀；空串 = 整段
   */
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

  /**
   * 解析事件史引用（{@code event <event_id>}；有「矛盾来源」块时只在块内解析，避免正文误命中）。
   *
   * @param text 待解析文本
   * @return 被引事件列表（去重保序）
   */
  public static List<EventRef> eventRefs(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    int header = text.indexOf(CITATION_HEADER);
    if (header < 0) {
      // ADR-0012 §2.6：只在「矛盾来源」块内解析——无块时不得把正文 "event loop" 之类当事件引用
      return List.of();
    }
    String scope = text.substring(header);
    List<EventRef> refs = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = EVENT_REF.matcher(scope);
    while (matcher.find()) {
      String eventId = matcher.group(1);
      if (seen.add(eventId)) {
        refs.add(new EventRef(eventId));
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
