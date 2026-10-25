package org.cra.common.git;

import java.time.Instant;
import java.util.Objects;

/**
 * 处置信号（06§1.1：对 bot 评论的 reply 标签 / Summary reaction；合并回填与行变更由 eval 侧按 PR diff 计算）。
 *
 * <p>三类信号来源中，<b>resolve 状态不可得</b>（PR review thread resolve 为 GraphQL-only，本项目锁 REST v3，
 * 05§2.3）——本类型不表达 resolve。不可得的平台返回空数组并声明"不采"（11§4 第 5 条）。
 *
 * <p>{@code kind} 取值：{@link #KIND_REPLY_TAG}（detail = wrong|intentional|fixed）、{@link
 * #KIND_REACTION} （detail = +1|-1，仅 Summary issue comment 的 👍/👎，记 run 满意度，不参与单 finding 分类）。
 *
 * @param kind 信号类别（reply_tag / reaction）
 * @param commentId 锚定的 bot 评论 id（reply 的信号锚定被回复的 bot 评论；reaction 锚定 Summary issue comment）
 * @param occurredAt 信号发生时间
 * @param detail 信号细目（reply 标签名 / reaction 内容），未知时为空串
 * @param actor 信号发出者 login（可空，空串表示平台未提供）
 * @param excerpt 信号原文摘要（reply 正文截断，审计用；可空）
 */
public record DispositionSignal(
    String kind, long commentId, Instant occurredAt, String detail, String actor, String excerpt) {

  /** 人类回复标签（06§1.1：REST replies 端点可读）。 */
  public static final String KIND_REPLY_TAG = "reply_tag";

  /** Summary issue comment 的 reaction（06§1.1：run 级满意度，不参与单 finding 分类）。 */
  public static final String KIND_REACTION = "reaction";

  /** reply 标签：误报（:wrong: / not-a-bug）。 */
  public static final String TAG_WRONG = "wrong";

  /** reply 标签：有意不改（:intentional:）。 */
  public static final String TAG_INTENTIONAL = "intentional";

  /** reply 标签：已按建议修复（:fixed:）。 */
  public static final String TAG_FIXED = "fixed";

  /** reaction：赞。 */
  public static final String REACTION_UP = "+1";

  /** reaction：踩。 */
  public static final String REACTION_DOWN = "-1";

  public DispositionSignal {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind must be non-blank");
    }
    if (commentId <= 0) {
      throw new IllegalArgumentException("commentId must be positive: " + commentId);
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    detail = detail == null ? "" : detail;
    actor = actor == null ? "" : actor;
    excerpt = excerpt == null ? "" : excerpt;
  }

  /**
   * 兼容 M0 形态的三参构造（既有调用方只带 kind/commentId/occurredAt）。
   *
   * @param kind 信号类别
   * @param commentId 锚定评论 id
   * @param occurredAt 发生时间
   */
  public DispositionSignal(String kind, long commentId, Instant occurredAt) {
    this(kind, commentId, occurredAt, "", "", "");
  }
}
