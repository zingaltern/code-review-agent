package com.example.pagination;

/** 分页默认值契约（INC-2145/commit 3a7f5c2）：对外默认页大小固定 20，企业前端控件按 20 分页渲染；禁止静默调整，调整须先过 UI 联调评审。 */
public final class PageSizePolicy {

  /** 契约版本（UI 联调评审记录）。 */
  public static final String CONTRACT_VERSION = "2026-09-20/ui-review";

  private PageSizePolicy() {}
}
