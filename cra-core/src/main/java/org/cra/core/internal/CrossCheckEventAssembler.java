package org.cra.core.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
import org.cra.core.internal.pipeline.CrossCheckEvents;

/**
 * CrossChecker 事件史装配（M4f-full，ADR-0012；03§3.1 输入 b 的事件面）。
 *
 * <p>查询面 = 本 PR 变更路径 + PR/变更史 commit + 由路径与 commit message 派生的关键词，窗口 = 近 N 周（与变更史同窗口）。 命中条目交给
 * {@link org.cra.core.internal.pipeline.CrossCheckMaterials} 按 03§6 预算裁剪后注入 brief。
 *
 * <p>降级（05§2.1 同 blame 模式）：未装配查询端口 / 检索失败 → available=false + 明确声明；不静默当作"已检查且无矛盾"。 tribal knowledge
 * 不可见是硬边界（01§3.4）——每次命中/无命中都声明覆盖范围。
 */
final class CrossCheckEventAssembler {

  /** 单次材料组装的最大事件数（03§6 预算内够用；更多靠排序而非堆砌）。 */
  static final int MAX_EVENTS = 20;

  /** 关键词上限（路径派生 + commit message 派生）。 */
  private static final int MAX_KEYWORDS = 12;

  /** 关键词最小长度（排除 src/main/java 这类无区分度 token）。 */
  private static final int MIN_KEYWORD_LENGTH = 4;

  private static final Set<String> STOP_WORDS =
      Set.of(
          "src",
          "main",
          "java",
          "test",
          "tests",
          "impl",
          "internal",
          "index",
          "data",
          "model",
          "util",
          "utils",
          "common",
          "core",
          "server",
          "code",
          "line",
          "file",
          "true",
          "false",
          "null",
          "void",
          "public",
          "private",
          "class",
          "return",
          "final",
          "import",
          "package");

  /** 硬边界声明（01§3.4：tribal knowledge 不可见）。 */
  static final String TRIBAL_KNOWLEDGE_NOTE =
      "事件史只覆盖已文档化入库的工单/事故复盘；tribal knowledge 不可见是硬边界（01§3.4）";

  private CrossCheckEventAssembler() {}

  /**
   * 查询与变更相关的事件史。
   *
   * @param query 事件检索端口（null = 未装配）
   * @param repo 仓库（owner/name，大小写不敏感）
   * @param paths 变更路径集
   * @param commitRefs 相关 commit（本 PR commit + 变更史 commit）
   * @param messages 变更说明（PR commit message，用于派生关键词）
   * @param weeks 回溯周数（与变更史同窗口）
   * @param clock 时钟（窗口锚点；测试可固定）
   * @return 事件史 + 声明
   */
  static CrossCheckEvents assemble(
      EventHistoryQuery query,
      String repo,
      Set<String> paths,
      Set<String> commitRefs,
      List<String> messages,
      int weeks,
      Clock clock) {
    List<String> notes = new ArrayList<>();
    notes.add(TRIBAL_KNOWLEDGE_NOTE);
    if (query == null) {
      notes.add("CrossChecker 事件史不可得（未装配事件查询端口）：模式 A 仅用变更史与 REVIEW.md 产品段");
      return new CrossCheckEvents(List.of(), false, notes);
    }
    List<String> keywords = keywords(paths, messages);
    Instant until = clock.instant();
    Instant since = until.minus((long) weeks * 7, ChronoUnit.DAYS);
    List<ProductEvent> entries;
    try {
      // 三类信号分别检索后取并集：工单往往没有 commit_refs、事故复盘不一定声明 paths——
      // 按 AND 组合会系统性漏召回（ADR-0012 §2.5）
      List<ProductEvent> byPath =
          paths.isEmpty()
              ? List.of()
              : query.search(
                  new EventQuery(
                      repo, sorted(paths), List.of(), List.of(), since, until, MAX_EVENTS));
      List<ProductEvent> byCommit =
          commitRefs.isEmpty()
              ? List.of()
              : query.search(
                  new EventQuery(
                      repo, List.of(), sorted(commitRefs), List.of(), since, until, MAX_EVENTS));
      List<ProductEvent> byKeyword =
          keywords.isEmpty()
              ? List.of()
              : query.search(
                  new EventQuery(repo, List.of(), List.of(), keywords, since, until, MAX_EVENTS));
      entries = merge(byPath, byCommit, byKeyword);
    } catch (RuntimeException e) {
      notes.add(
          "CrossChecker 事件史检索失败（"
              + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage())
              + "），模式 A 仅用变更史与产品段");
      return new CrossCheckEvents(List.of(), false, notes);
    }
    notes.add(
        "CrossChecker 事件史：近 "
            + weeks
            + " 周命中 "
            + entries.size()
            + " 条（变更路径 "
            + paths.size()
            + " 个 / commit "
            + commitRefs.size()
            + " 个 / 关键词 "
            + keywords.size()
            + " 个；三类信号并集去重后按时间倒序）");
    return new CrossCheckEvents(entries, true, notes);
  }

  private static List<ProductEvent> merge(
      List<ProductEvent> first, List<ProductEvent> second, List<ProductEvent> third) {
    Map<String, ProductEvent> unique = new LinkedHashMap<>();
    for (List<ProductEvent> source : List.of(first, second, third)) {
      if (source == null) {
        continue;
      }
      for (ProductEvent event : source) {
        unique.putIfAbsent(event.repo() + "\u0000" + event.eventId(), event);
      }
    }
    List<ProductEvent> entries = new ArrayList<>(unique.values());
    entries.sort(
        Comparator.comparing(ProductEvent::occurredAt)
            .reversed()
            .thenComparing(ProductEvent::eventId));
    return entries.size() > MAX_EVENTS
        ? List.copyOf(entries.subList(0, MAX_EVENTS))
        : List.copyOf(entries);
  }

  /**
   * 从变更路径与 commit message 派生检索关键词（确定性；去重保序；最多 {@value #MAX_KEYWORDS} 个）。
   *
   * @param paths 变更路径
   * @param messages commit message / PR 标题（可空）
   * @return 关键词
   */
  static List<String> keywords(Set<String> paths, List<String> messages) {
    Set<String> out = new LinkedHashSet<>();
    for (String path : sorted(paths)) {
      String file = path;
      int slash = file.lastIndexOf('/');
      if (slash >= 0) {
        file = file.substring(slash + 1);
      }
      int dot = file.lastIndexOf('.');
      String base = dot > 0 ? file.substring(0, dot) : file;
      addToken(out, base);
      for (String part : splitCamel(base)) {
        addToken(out, part);
      }
      if (out.size() >= MAX_KEYWORDS) {
        break;
      }
    }
    if (messages != null) {
      for (String message : messages) {
        if (message == null) {
          continue;
        }
        for (String token : message.split("[^A-Za-z0-9_]+")) {
          addToken(out, token);
          if (out.size() >= MAX_KEYWORDS) {
            break;
          }
        }
        if (out.size() >= MAX_KEYWORDS) {
          break;
        }
      }
    }
    return List.copyOf(out);
  }

  private static void addToken(Set<String> out, String raw) {
    if (out.size() >= MAX_KEYWORDS || raw == null) {
      return;
    }
    String token = raw.strip().toLowerCase(Locale.ROOT);
    if (token.length() < MIN_KEYWORD_LENGTH || STOP_WORDS.contains(token)) {
      return;
    }
    out.add(token);
  }

  private static List<String> splitCamel(String value) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isUpperCase(c) && current.length() > 0) {
        parts.add(current.toString());
        current.setLength(0);
      }
      if (Character.isLetterOrDigit(c)) {
        current.append(c);
      } else if (current.length() > 0) {
        parts.add(current.toString());
        current.setLength(0);
      }
    }
    if (current.length() > 0) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static List<String> sorted(Set<String> values) {
    List<String> out = new ArrayList<>(values == null ? Set.of() : values);
    out.sort(String::compareTo);
    return List.copyOf(out);
  }
}
