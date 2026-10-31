package org.cra.common.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 事件史检索条件（ADR-0012；{@link EventHistoryQuery} 的入参）。
 *
 * <p>过滤语义：各项条件之间 AND；同一项内的列表之间 OR。空列表/空值 = 不过滤。
 *
 * @param repo 仓库（{@code owner/name}；统一小写，null = 不限定仓库）
 * @param paths 变更路径（事件声明路径命中任一即可，规则见 {@link EventPathMatcher}）
 * @param commitRefs 关联 commit（事件 commit_refs 与之前缀互配）
 * @param keywords 关键词（大小写不敏感的摘要/原始 JSON 子串匹配）
 * @param since 发生时间下界（含；null = 不限）
 * @param until 发生时间上界（含；null = 不限）
 * @param limit 返回上限（1–200；默认 50）
 */
public record EventQuery(
    String repo,
    List<String> paths,
    List<String> commitRefs,
    List<String> keywords,
    Instant since,
    Instant until,
    int limit) {

  /** 默认返回上限（CrossChecker 单次材料组装的候选数）。 */
  public static final int DEFAULT_LIMIT = 50;

  public EventQuery {
    repo = repo == null || repo.isBlank() ? null : repo.strip().toLowerCase(Locale.ROOT);
    paths = normalizePaths(paths);
    commitRefs = normalizeCommitRefs(commitRefs);
    keywords = normalizeKeywords(keywords);
    if (since != null && until != null && since.isAfter(until)) {
      throw new IllegalArgumentException("since 晚于 until: " + since + " > " + until);
    }
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    limit = Math.min(limit, 200);
  }

  /**
   * 简化构造（只按仓库 + 时间窗口）。
   *
   * @param repo 仓库
   * @param since 下界
   * @param limit 上限
   * @return 查询
   */
  public static EventQuery of(String repo, Instant since, int limit) {
    return new EventQuery(repo, List.of(), List.of(), List.of(), since, null, limit);
  }

  private static List<String> normalizePaths(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String path : paths) {
      String normalized = EventPathMatcher.normalize(path);
      if (!normalized.isEmpty()) {
        out.add(normalized);
      }
    }
    List<String> sorted = new ArrayList<>(out);
    sorted.sort(String::compareTo);
    return List.copyOf(sorted);
  }

  private static List<String> normalizeCommitRefs(List<String> refs) {
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    return refs.stream()
        .filter(ref -> ref != null && !ref.isBlank())
        .map(ref -> ref.strip().toLowerCase(Locale.ROOT))
        .collect(
            Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new), List::copyOf));
  }

  private static List<String> normalizeKeywords(List<String> keywords) {
    if (keywords == null || keywords.isEmpty()) {
      return List.of();
    }
    return keywords.stream()
        .filter(keyword -> keyword != null && !keyword.isBlank())
        .map(String::strip)
        .collect(
            Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new), List::copyOf));
  }
}
