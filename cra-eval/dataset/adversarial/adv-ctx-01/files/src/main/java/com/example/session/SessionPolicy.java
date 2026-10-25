package com.example.session;

/** 会话上限策略（企业长会话定制，commit 9c41d2a 引入）。 */
public final class SessionPolicy {

  /** 企业客户会话硬上限（分钟）。 */
  public static final int ENTERPRISE_MAX_SESSION_MINUTES = 30;

  private SessionPolicy() {}
}
