package org.cra.context.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cra.common.context.ReviewPolicy;
import org.cra.context.api.ReviewPolicyParser;

/**
 * REVIEW.md 解析（04§3.1）：按 {@code ## <heading>} 切段，段内 {@code - } 列表项为条目； Nit budget 数值写在标题内（{@code ##
 * Nit budget: 3}）。三类新段落（Product context / Known incidents / Sniff
 * tests）与既有段落同机制；段落缺失优雅跳过；未知标题忽略（前向兼容）；全文无任何可识别段落时按最小默认策略 + 警告（04§3.2 规则 4）。
 */
public final class DefaultReviewPolicyParser implements ReviewPolicyParser {

  @Override
  public ReviewPolicy parse(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return ReviewPolicy.empty();
    }
    List<String> warnings = new ArrayList<>();
    Map<String, Section> sections = splitSections(markdown, warnings);

    Integer nitBudget = parseNitBudget(sections.get("nit budget"), warnings);
    List<String> failure = items(sections, "what counts as failure");
    List<String> priority = items(sections, "priority paths");
    List<String> skip = items(sections, "skip paths");
    List<String> evidence = items(sections, "evidence requirements");
    List<String> conventions = items(sections, "conventions");
    List<String> productContext = items(sections, "product context");
    List<String> incidents = items(sections, "known incidents");
    List<String> sniff = items(sections, "sniff tests");

    boolean noContent =
        nitBudget == null
            && failure.isEmpty()
            && priority.isEmpty()
            && skip.isEmpty()
            && evidence.isEmpty()
            && conventions.isEmpty()
            && productContext.isEmpty()
            && incidents.isEmpty()
            && sniff.isEmpty();
    if (noContent && warnings.isEmpty()) {
      warnings.add("REVIEW.md 未识别任何评审策略段落，按最小默认策略执行（04§3.2）");
    }
    return new ReviewPolicy(
        true,
        warnings,
        nitBudget,
        priority,
        skip,
        failure,
        evidence,
        conventions,
        productContext,
        incidents,
        sniff);
  }

  private Map<String, Section> splitSections(String markdown, List<String> warnings) {
    Map<String, Section> out = new LinkedHashMap<>();
    Section current = null;
    for (String rawLine : markdown.split("\n", -1)) {
      String line = rawLine.stripTrailing();
      if (line.startsWith("## ") && !line.startsWith("###")) {
        String heading = line.substring(3).strip();
        // 重复标题复用首个段落：putIfAbsent 会让 current 指向不在 map 里的孤儿 Section，
        // 其后所有列表项被静默丢弃（Skip paths/Conventions 丢失会直接改变评审范围）
        current = out.computeIfAbsent(normalizeKey(heading), key -> new Section(heading));
        continue;
      }
      if (line.startsWith("#")) {
        continue; // 一级/三级及更深标题不构成段落边界（### 是列表内常见强调形态）
      }
      if (current != null && line.strip().startsWith("- ")) {
        current.items.add(line.strip().substring(2).strip());
      } else if (current != null && !line.isBlank() && !line.strip().startsWith("- ")) {
        // 非列表正文（如 Nit budget 的裸数值行）保留为杂项文本
        current.freeText.add(line.strip());
      }
    }
    if (out.isEmpty() && !markdown.isBlank()) {
      warnings.add("REVIEW.md 无二级标题结构，无法解析策略段落，按最小默认策略执行（04§3.2）");
    }
    return out;
  }

  private Integer parseNitBudget(Section section, List<String> warnings) {
    if (section == null) {
      return null;
    }
    // 优先标题内嵌值（## Nit budget: 3），退回段内裸数值行
    String source =
        section.heading.contains(":")
            ? section.heading.substring(section.heading.indexOf(':') + 1)
            : section.freeText.stream().findFirst().orElse(null);
    if (source == null || source.isBlank()) {
      warnings.add("Nit budget 段未提供数值，按未配置处理");
      return null;
    }
    try {
      int value = Integer.parseInt(source.strip());
      if (value < 0) {
        throw new NumberFormatException();
      }
      return value;
    } catch (NumberFormatException e) {
      warnings.add("Nit budget 数值非法: " + source.strip() + "，按未配置处理");
      return null;
    }
  }

  private List<String> items(Map<String, Section> sections, String key) {
    Section section = sections.get(key);
    return section == null ? List.of() : List.copyOf(section.items);
  }

  private static String normalizeKey(String heading) {
    String base = heading.contains(":") ? heading.substring(0, heading.indexOf(':')) : heading;
    return base.strip().toLowerCase(Locale.ROOT);
  }

  /** 单个二级标题段落：列表项 + 杂项文本。 */
  private static final class Section {
    final String heading;
    final List<String> items = new ArrayList<>();
    final List<String> freeText = new ArrayList<>();

    Section(String heading) {
      this.heading = heading;
    }
  }
}
