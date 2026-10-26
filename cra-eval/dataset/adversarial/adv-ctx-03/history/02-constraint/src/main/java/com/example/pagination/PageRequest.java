package com.example.pagination;

/** 分页请求（页码从 1 起）。 */
public final class PageRequest {

  /** 对外默认页大小。 */
  public static final int DEFAULT_PAGE_SIZE = 20;

  private final int page;

  private final int pageSize;

  public PageRequest(int page, int pageSize) {
    this.page = Math.max(1, page);
    this.pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
  }

  public int page() {
    return page;
  }

  public int pageSize() {
    return pageSize;
  }
}
