package org.cra.core.internal;

import java.util.List;
import java.util.Optional;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.event.ProductEvent;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.ContextCitation;
import org.cra.git.api.LocalRepoContext;

/**
 * 被引矛盾证据解析（03§4.2 v1.3.1：context 类 finding 引用的 commit diff 节选，编排器预取内联， 每 commit ≤1k
 * token）。引用格式的权威定义见 {@link ContextCitation}（M4f 落定），经本地 jgit 读取（{@link
 * LocalRepoContext#commitFilePatch}）。
 *
 * <p>M4f-full（ADR-0012）：{@code event <event_id>} 引用经 {@link EventHistoryQuery} 预取事件条目（摘要/类型/时间/来源），
 * 与 commit diff 同一信任口径（事件摘要属不可信内容，brief 中定界）。
 *
 * <p>best effort：本地仓库不可得 / commit 不可解析 / 文件不在该 commit 内 / 事件不在事件史 → 空串（组装器降级为 finding 自带证据原文）。
 */
final class CitedEvidenceResolver {

  private final LocalRepoContext localRepoContext;
  private final EventHistoryQuery eventHistoryQuery;

  /**
   * @param localRepoContext 本地仓库上下文（null = 纯 API 形态，全部降级为空）
   */
  CitedEvidenceResolver(LocalRepoContext localRepoContext) {
    this(localRepoContext, null);
  }

  /**
   * @param eventHistoryQuery 事件史查询端口（null = 未装配，事件引用降级为 finding 自带证据）
   */
  CitedEvidenceResolver(LocalRepoContext localRepoContext, EventHistoryQuery eventHistoryQuery) {
    this.localRepoContext = localRepoContext;
    this.eventHistoryQuery = eventHistoryQuery;
  }

  /**
   * 一条 finding 的全部机器可解析引用（commit + REVIEW.md 条目锚 + 事件；事件不解析）。
   *
   * @param finding 候选结论
   * @return 引用集合
   */
  Citation citationOf(CandidateFinding finding) {
    return citationOf(finding, null);
  }

  /**
   * 一条 finding 的全部机器可解析引用（M4f-full：按 repo 解析事件引用）。
   *
   * @param finding 候选结论
   * @param repo 仓库（owner/name；null = 不解析事件）
   * @return 引用集合
   */
  Citation citationOf(CandidateFinding finding, String repo) {
    String text = finding.evidence() + "\n" + finding.description();
    List<ContextCitation.EventRef> refs = ContextCitation.eventRefs(text);
    List<EventEvidence> events =
        refs.stream()
            .map(ref -> new EventEvidence(ref, eventDetails(repo, ref.eventId()).orElse(null)))
            .toList();
    return new Citation(
        ContextCitation.commitRefs(text, finding.file()),
        ContextCitation.reviewMdRefs(text),
        events);
  }

  /**
   * 取被引 commit 对文件的 unified patch。
   *
   * @param sha 被引 commit（完整或缩写）
   * @param path commit 内文件路径
   * @return patch 文本；不可得为空串
   */
  String commitPatch(String sha, String path) {
    if (localRepoContext == null) {
      return "";
    }
    try {
      Optional<String> patch = localRepoContext.commitFilePatch(sha, path);
      return patch.orElse("");
    } catch (RuntimeException e) {
      return "";
    }
  }

  /**
   * 取被引事件条目（ADR-0012）。
   *
   * @param repo 仓库（owner/name）
   * @param eventId 事件标识
   * @return 事件；未装配/不存在/检索失败为空
   */
  Optional<ProductEvent> eventDetails(String repo, String eventId) {
    if (eventHistoryQuery == null || repo == null || repo.isBlank() || eventId == null) {
      return Optional.empty();
    }
    try {
      return eventHistoryQuery.find(repo, eventId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * 被引事件（ref + 解析出的事件条目；未解析时 event=null）。
   *
   * @param ref 解析出的引用
   * @param event 事件条目（可空）
   */
  record EventEvidence(ContextCitation.EventRef ref, ProductEvent event) {

    /**
     * 是否解析到事件条目。
     *
     * @return true 若事件存在于事件史
     */
    boolean resolved() {
      return event != null;
    }
  }

  /**
   * 引用集合。
   *
   * @param commits 被引 commit（结构化引用优先，裸 hex 兼容）
   * @param reviewMdRefs 被引 REVIEW.md 条目（段名规范化）
   * @param events 被引事件（M4f-full；含未解析引用）
   */
  record Citation(
      List<ContextCitation.CommitRef> commits,
      List<ContextCitation.ReviewMdRef> reviewMdRefs,
      List<EventEvidence> events) {

    Citation {
      commits = List.copyOf(commits == null ? List.of() : commits);
      reviewMdRefs = List.copyOf(reviewMdRefs == null ? List.of() : reviewMdRefs);
      events = List.copyOf(events == null ? List.of() : events);
    }

    /**
     * 是否无任何引用。
     *
     * @return true 若无引用
     */
    boolean isEmpty() {
      return commits.isEmpty() && reviewMdRefs.isEmpty() && events.isEmpty();
    }
  }
}
