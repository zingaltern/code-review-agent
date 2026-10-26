package com.example.pagination;

/** 分页请求（页码从 1 起）。 */
public final class PageRequest {

  private final int page;

  private final int pageSize;

  public PageRequest(int page, int pageSize) {
    this.page = Math.max(1, page);
    this.pageSize = pageSize <= 0 ? 20 : pageSize;
  }

  public int page() {
    return page;
  }

  public int pageSize() {
    return pageSize;
  }
}
