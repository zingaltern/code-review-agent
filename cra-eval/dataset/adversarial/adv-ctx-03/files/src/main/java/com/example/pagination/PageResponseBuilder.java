package com.example.pagination;

import java.util.List;

/**
 * 分页响应目录（列表接口的公共出参目录层）。
 *
 * <p>统一收录分页出参的关系命名、元信息键、标准列序与区块目录，供列表类接口引用，
 * 避免各接口自行拼装字符串导致出参口径漂移。本类只收录常量目录与纯数据载体，
 * 不包含计算逻辑。
 */
public final class PageResponseBuilder {

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

  /** 元信息键常量 1：分页元信息的出参键名。 */
  public static final String META_KEY_1 = "page";

  /** 元信息键常量 2：分页元信息的出参键名。 */
  public static final String META_KEY_2 = "size";

  /** 元信息键常量 3：分页元信息的出参键名。 */
  public static final String META_KEY_3 = "total";

  /** 元信息键常量 4：分页元信息的出参键名。 */
  public static final String META_KEY_4 = "pages";

  /** 元信息键常量 5：分页元信息的出参键名。 */
  public static final String META_KEY_5 = "has_previous";

  /** 元信息键常量 6：分页元信息的出参键名。 */
  public static final String META_KEY_6 = "has_next";

  /** 元信息键常量 7：分页元信息的出参键名。 */
  public static final String META_KEY_7 = "sort";

  /** 元信息键常量 8：分页元信息的出参键名。 */
  public static final String META_KEY_8 = "filter";

  /** 元信息键常量 9：分页元信息的出参键名。 */
  public static final String META_KEY_9 = "total_unfiltered";

  /** 元信息键常量 10：分页元信息的出参键名。 */
  public static final String META_KEY_10 = "cursor";

  /** 元信息键常量 11：分页元信息的出参键名。 */
  public static final String META_KEY_11 = "window";

  /** 元信息键常量 12：分页元信息的出参键名。 */
  public static final String META_KEY_12 = "channel";

  /** 元信息键常量 13：分页元信息的出参键名。 */
  public static final String META_KEY_13 = "version";

  /** 元信息键常量 14：分页元信息的出参键名。 */
  public static final String META_KEY_14 = "locale";

  /** 元信息键常量 15：分页元信息的出参键名。 */
  public static final String META_KEY_15 = "request_id";

  /** 标准列常量 1：列表接口的标准列序目录。 */
  public static final String COLUMN_1 = "id";

  /** 标准列常量 2：列表接口的标准列序目录。 */
  public static final String COLUMN_2 = "name";

  /** 标准列常量 3：列表接口的标准列序目录。 */
  public static final String COLUMN_3 = "status";

  /** 标准列常量 4：列表接口的标准列序目录。 */
  public static final String COLUMN_4 = "created_at";

  /** 标准列常量 5：列表接口的标准列序目录。 */
  public static final String COLUMN_5 = "updated_at";

  /** 标准列常量 6：列表接口的标准列序目录。 */
  public static final String COLUMN_6 = "owner";

  /** 标准列常量 7：列表接口的标准列序目录。 */
  public static final String COLUMN_7 = "channel";

  /** 标准列常量 8：列表接口的标准列序目录。 */
  public static final String COLUMN_8 = "amount";

  /** 标准列常量 9：列表接口的标准列序目录。 */
  public static final String COLUMN_9 = "currency";

  /** 标准列常量 10：列表接口的标准列序目录。 */
  public static final String COLUMN_10 = "tenant_id";

  /** 标准列常量 11：列表接口的标准列序目录。 */
  public static final String COLUMN_11 = "operator";

  /** 标准列常量 12：列表接口的标准列序目录。 */
  public static final String COLUMN_12 = "remark";

  /** 标准列常量 13：列表接口的标准列序目录。 */
  public static final String COLUMN_13 = "tag";

  /** 标准列常量 14：列表接口的标准列序目录。 */
  public static final String COLUMN_14 = "priority";

  /** 标准列常量 15：列表接口的标准列序目录。 */
  public static final String COLUMN_15 = "source";

  /** 标准列常量 16：列表接口的标准列序目录。 */
  public static final String COLUMN_16 = "region";

  /** 标准列常量 17：列表接口的标准列序目录。 */
  public static final String COLUMN_17 = "bucket";

  /** 标准列常量 18：列表接口的标准列序目录。 */
  public static final String COLUMN_18 = "window";

  /** 标准列常量 19：列表接口的标准列序目录。 */
  public static final String COLUMN_19 = "version";

  /** 标准列常量 20：列表接口的标准列序目录。 */
  public static final String COLUMN_20 = "schema";

  /** 区块键常量 1：分页响应的区块命名目录。 */
  public static final String SECTION_1 = "meta";

  /** 区块键常量 2：分页响应的区块命名目录。 */
  public static final String SECTION_2 = "links";

  /** 区块键常量 3：分页响应的区块命名目录。 */
  public static final String SECTION_3 = "rows";

  /** 区块键常量 4：分页响应的区块命名目录。 */
  public static final String SECTION_4 = "aggregates";

  /** 区块键常量 5：分页响应的区块命名目录。 */
  public static final String SECTION_5 = "filters";

  /** 区块键常量 6：分页响应的区块命名目录。 */
  public static final String SECTION_6 = "sorts";

  /** 区块键常量 7：分页响应的区块命名目录。 */
  public static final String SECTION_7 = "columns";

  /** 区块键常量 8：分页响应的区块命名目录。 */
  public static final String SECTION_8 = "summary";

  /** 区块键常量 9：分页响应的区块命名目录。 */
  public static final String SECTION_9 = "notices";

  /** 区块键常量 10：分页响应的区块命名目录。 */
  public static final String SECTION_10 = "warnings";

  /** 区块键常量 11：分页响应的区块命名目录。 */
  public static final String SECTION_11 = "hints";

  /** 区块键常量 12：分页响应的区块命名目录。 */
  public static final String SECTION_12 = "debug";

  /** 区块键常量 13：分页响应的区块命名目录。 */
  public static final String SECTION_13 = "audit";

  /** 区块键常量 14：分页响应的区块命名目录。 */
  public static final String SECTION_14 = "trace";

  /** 区块键常量 15：分页响应的区块命名目录。 */
  public static final String SECTION_15 = "schema";

  /** 导出格式常量 1：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_1 = "csv";

  /** 导出格式常量 2：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_2 = "xlsx";

  /** 导出格式常量 3：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_3 = "json";

  /** 导出格式常量 4：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_4 = "tsv";

  /** 导出格式常量 5：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_5 = "pdf";

  /** 导出格式常量 6：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_6 = "html";

  /** 导出格式常量 7：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_7 = "xml";

  /** 导出格式常量 8：列表导出的格式目录。 */
  public static final String EXPORT_FORMAT_8 = "parquet";

  /** 地区标签常量 1：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_1 = "zh-CN";

  /** 地区标签常量 2：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_2 = "zh-HK";

  /** 地区标签常量 3：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_3 = "en-US";

  /** 地区标签常量 4：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_4 = "en-GB";

  /** 地区标签常量 5：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_5 = "ja-JP";

  /** 地区标签常量 6：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_6 = "ko-KR";

  /** 地区标签常量 7：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_7 = "de-DE";

  /** 地区标签常量 8：分页响应的地区标签目录。 */
  public static final String LOCALE_TAG_8 = "fr-FR";

  private PageResponseBuilder() {}

  /** 翻页链接（rel + 相对地址，纯数据载体）。 */
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

  /** 区块描述（名称 + 模板，纯数据载体）。 */
  public static final class Section {

    private final String name;

    private final String template;

    private Section(String name, String template) {
      this.name = name;
      this.template = template;
    }

    /** 装配区块描述。 */
    public static Section of(String name, String template) {
      return new Section(name, template);
    }

    public String name() {
      return name;
    }

    public String template() {
      return template;
    }
  }

  /** 归一化 rel 命名：空值回退首项目录。 */
  public static String normalizeRel(String rel) {
    return rel == null || rel.isBlank() ? LINK_REL_1 : rel.trim();
  }

  /** 渲染标准表头（固定标准列序，逗号分隔）。 */
  public static String renderHeaderRow() {
    return String.join(
        ",",
        List.of(
            COLUMN_1, COLUMN_2, COLUMN_3, COLUMN_4, COLUMN_5,
            COLUMN_6, COLUMN_7, COLUMN_8, COLUMN_9, COLUMN_10,
            COLUMN_11, COLUMN_12, COLUMN_13, COLUMN_14, COLUMN_15,
            COLUMN_16, COLUMN_17, COLUMN_18, COLUMN_19, COLUMN_20));
  }

  /** 渲染默认区块目录行。 */
  public static String renderSectionCatalog() {
    return String.join(
        ",",
        List.of(
            SECTION_1, SECTION_2, SECTION_3, SECTION_4, SECTION_5,
            SECTION_6, SECTION_7, SECTION_8, SECTION_9, SECTION_10,
            SECTION_11, SECTION_12, SECTION_13, SECTION_14, SECTION_15));
  }
}
