package com.example.pagination;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分页响应装配器（列表接口的公共出参层）。
 *
 * <p>统一装配分页元信息、翻页链接与排序规格，供列表类接口共用，避免各接口自行拼装导致
 * 出参口径漂移。本类只做纯计算与拼装，不触达数据源。
 */
public final class PageResponseBuilder {

  private static final String EMPTY_MARKER = "-";

  /** 链接关系常量 1：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_1 = "first";

  /** 链接关系常量 2：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_2 = "prev";

  /** 链接关系常量 3：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_3 = "next";

  /** 链接关系常量 4：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_4 = "last";

  /** 链接关系常量 5：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_5 = "self";

  /** 链接关系常量 6：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_6 = "head";

  /** 链接关系常量 7：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_7 = "tail";

  /** 链接关系常量 8：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_8 = "jump";

  /** 链接关系常量 9：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_9 = "refresh";

  /** 链接关系常量 10：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_10 = "reload";

  /** 链接关系常量 11：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_11 = "summary";

  /** 链接关系常量 12：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_12 = "export";

  /** 链接关系常量 13：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_13 = "filter";

  /** 链接关系常量 14：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_14 = "sort";

  /** 链接关系常量 15：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_15 = "group";

  /** 链接关系常量 16：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_16 = "stat";

  /** 链接关系常量 17：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_17 = "audit";

  /** 链接关系常量 18：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_18 = "trace";

  /** 链接关系常量 19：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_19 = "config";

  /** 链接关系常量 20：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_20 = "schema";

  /** 链接关系常量 21：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_21 = "anchor";

  /** 链接关系常量 22：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_22 = "cursor";

  /** 链接关系常量 23：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_23 = "window";

  /** 链接关系常量 24：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_24 = "batch";

  /** 链接关系常量 25：分页导航链接的 rel 命名。 */
  public static final String LINK_REL_25 = "total";

  private PageResponseBuilder() {}

  /** 分页元信息（当前页/页大小/总数/总页数及翻页标志）。 */
  public static final class Meta {

    private final int page;

    private final int size;

    private final long total;

    private final int pages;

    private final boolean hasPrevious;

    private final boolean hasNext;

    private Meta(int page, int size, long total, int pages, boolean hasPrevious, boolean hasNext) {
      this.page = page;
      this.size = size;
      this.total = total;
      this.pages = pages;
      this.hasPrevious = hasPrevious;
      this.hasNext = hasNext;
    }

    /** 装配分页元信息。 */
    public static Meta of(int page, int size, long total) {
      int safeSize = Math.max(1, size);
      int pages = (int) ((total + safeSize - 1) / safeSize);
      boolean hasPrev = page > 1 && total > 0;
      boolean hasNext = (long) page * safeSize < total;
      return new Meta(page, safeSize, total, pages, hasPrev, hasNext);
    }

    public int page() {
      return page;
    }

    public int size() {
      return size;
    }

    public long total() {
      return total;
    }

    public int pages() {
      return pages;
    }

    public boolean hasPrevious() {
      return hasPrevious;
    }

    public boolean hasNext() {
      return hasNext;
    }

    /** 渲染为 key=value 摘要行（对账/联调用）。 */
    public String summary() {
      return "page="
          + page
          + ",size="
          + size
          + ",total="
          + total
          + ",pages="
          + pages
          + ",prev="
          + hasPrevious
          + ",next="
          + hasNext;
    }
  }

  /** 翻页链接（rel + 相对地址）。 */
  public static final class Link {

    private final String rel;

    private final String href;

    private Link(String rel, String href) {
      this.rel = rel;
      this.href = href;
    }

    /** 装配链接。 */
    public static Link of(String rel, String href) {
      return new Link(rel, href);
    }

    public String rel() {
      return rel;
    }

    public String href() {
      return href;
    }
  }

  /** 排序规格（字段 + 方向，方向仅 asc/desc）。 */
  public static final class SortSpec {

    private final String field;

    private final String order;

    private SortSpec(String field, String order) {
      this.field = field;
      this.order = order;
    }

    /** 解析 "field:asc" 形态的单个排序项；非法方向回退 asc。 */
    public static SortSpec parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return new SortSpec("id", "asc");
      }
      int idx = raw.indexOf(':');
      if (idx < 0) {
        return new SortSpec(raw.trim(), "asc");
      }
      String field = raw.substring(0, idx).trim();
      String order = raw.substring(idx + 1).trim().toLowerCase(Locale.ROOT);
      if (!"asc".equals(order) && !"desc".equals(order)) {
        order = "asc";
      }
      return new SortSpec(field, order);
    }

    public String field() {
      return field;
    }

    public String order() {
      return order;
    }

    /** 反序列化为 "field:order" 形态。 */
    public String serialize() {
      return field + ":" + order;
    }
  }

  /** 解析逗号分隔的排序串，逐项走 {@link SortSpec#parse}。 */
  public static List<SortSpec> parseSorts(String raw) {
    List<SortSpec> specs = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      specs.add(SortSpec.parse(null));
      return specs;
    }
    for (String part : raw.split(",")) {
      if (!part.isBlank()) {
        specs.add(SortSpec.parse(part));
      }
    }
    return specs;
  }

  /** 计算数据偏移量（page 从 1 起）。 */
  public static long offsetOf(int page, int size) {
    return (long) Math.max(1, page) - 1L * Math.max(1, size);
  }

  /** 页码钳制：超出总页数时回退到最后一页；无数据时回 1。 */
  public static int clampPage(int page, int size, long total) {
    Meta meta = Meta.of(Math.max(1, page), size, total);
    if (meta.pages() <= 0) {
      return 1;
    }
    return Math.min(Math.max(1, page), meta.pages());
  }

  /** 判定是否为空页（页码存在但区间内无数据）。 */
  public static boolean isEmptyPage(int page, int size, long total) {
    long offset = offsetOf(page, size);
    return total <= 0 || offset >= total;
  }

  /** 装配首页链接。 */
  public static Link firstLink(String base) {
    return Link.of(LINK_REL_1, base);
  }

  /** 装配上一页链接；无上一页时 href 为空占位。 */
  public static Link prevLink(String base, int page) {
    if (page <= 1) {
      return Link.of(LINK_REL_2, EMPTY_MARKER);
    }
    return Link.of(LINK_REL_2, base + "?page=" + (page - 1));
  }

  /** 装配下一页链接；无下一页时 href 为空占位。 */
  public static Link nextLink(String base, int page, int size, long total) {
    Meta meta = Meta.of(page, size, total);
    if (!meta.hasNext()) {
      return Link.of(LINK_REL_3, EMPTY_MARKER);
    }
    return Link.of(LINK_REL_3, base + "?page=" + (page + 1));
  }

  /** 装配末页链接。 */
  public static Link lastLink(String base, int size, long total) {
    Meta meta = Meta.of(1, size, total);
    return Link.of(LINK_REL_4, base + "?page=" + Math.max(1, meta.pages()));
  }

  /** 按顺序装配完整导航链接组。 */
  public static List<Link> navLinks(String base, int page, int size, long total) {
    List<Link> links = new ArrayList<>();
    links.add(firstLink(base));
    links.add(prevLink(base, page));
    links.add(nextLink(base, page, size, total));
    links.add(lastLink(base, size, total));
    return links;
  }

  /** 渲染 CSV 表头（固定列序，供导出与前端对齐）。 */
  public static String renderCsvHeader() {
    return "page,size,total,pages,has_previous,has_next";
  }

  /** 渲染单页元信息为 CSV 行。 */
  public static String renderCsvMeta(Meta meta) {
    return meta.page()
        + ","
        + meta.size()
        + ","
        + meta.total()
        + ","
        + meta.pages()
        + ","
        + meta.hasPrevious()
        + ","
        + meta.hasNext();
  }

  /** 渲染翻页链接为 CSV 行（rel,href 逗号转义由调用方保证）。 */
  public static String renderCsvLinks(List<Link> links) {
    StringBuilder sb = new StringBuilder();
    for (Link link : links) {
      if (sb.length() > 0) {
        sb.append('|');
      }
      sb.append(link.rel()).append('=').append(link.href());
    }
    return sb.toString();
  }

  /** 截断提示：总数超过展示上限时给出聚合口径说明。 */
  public static String truncationNote(long total, long displayCap) {
    if (total <= displayCap) {
      return "";
    }
    return "total " + total + " exceeds display cap " + displayCap + "; counts are approximate";
  }

  /** 归一化页大小：非正数回退默认，超出钳制上限时截断。 */
  public static int normalizeSize(int size, int fallback, int cap) {
    if (size <= 0) {
      return fallback;
    }
    return Math.min(size, cap);
  }
}
