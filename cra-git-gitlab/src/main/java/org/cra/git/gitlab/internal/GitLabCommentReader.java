package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.DispositionSupport;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerMarkerParser;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitLab 账本 / 处置 / 周报列表读侧（CommentSource 实现，05§1.3、05§2.3、06§1.1、ADR-0011）。
 *
 * <p>从 {@link GitLabHttpAdapter} 拆出以控制上帝类行数（10§8）：本类只做 MR/commit/issue 评论与列表取数、标记解析与分页；发布与 diff
 * 读取仍在 adapter。分页触顶一律显式失败（含第 101 页探测），不返回部分结果。
 */
final class GitLabCommentReader implements CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabCommentReader.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final GitLabHttpTransport transport;
  private final String botUsernameFallback;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private volatile String cachedUsername;

  GitLabCommentReader(GitLabHttpTransport transport, String botUsernameFallback) {
    this.transport = transport;
    this.botUsernameFallback =
        botUsernameFallback == null || botUsernameFallback.isBlank() ? null : botUsernameFallback;
  }

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    String login = botUsername(repo);
    List<LedgerComment> comments = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (JsonNode discussion :
        getPagedArray(repo, "/merge_requests/" + prNumber + "/discussions")) {
      for (JsonNode note : discussion.path("notes")) {
        long id = note.path("id").asLong(0);
        // 只认 bot 自身评论（账本语义，02§5）；id 去重防同一 note 跨 discussion 重复
        if (!note.path("system").asBoolean(false)
            && login.equals(note.path("author").path("username").asText(""))
            && id > 0
            && seen.add(id)) {
          JsonNode position = note.path("position");
          boolean inline = position.isObject();
          comments.add(
              new LedgerComment(
                  id,
                  inline ? LedgerComment.KIND_INLINE : LedgerComment.KIND_ISSUE,
                  login,
                  note.path("body").asText(""),
                  DispositionSupport.instantOrEpoch(note.path("created_at")),
                  inline ? GitLabJsonSupport.textOrNull(position, "head_sha") : null));
        }
      }
    }
    return GitLabLedgerSupport.parse(comments, markerParser);
  }

  @Override
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    String login = botUsername(repo);
    List<LedgerComment> comments = new ArrayList<>();
    for (JsonNode c :
        getPagedArray(
            repo,
            "/repository/commits/" + GitLabPayloadSupport.urlEncode(commitSha) + "/comments")) {
      if (!login.equals(c.path("author").path("username").asText(""))) {
        continue;
      }
      String note = c.path("note").asText("");
      comments.add(
          new LedgerComment(
              GitLabLedgerSupport.syntheticCommitCommentId(commitSha, note),
              LedgerComment.KIND_COMMIT,
              login,
              note,
              DispositionSupport.instantOrEpoch(c.path("created_at")),
              commitSha));
    }
    return GitLabLedgerSupport.parse(comments, markerParser);
  }

  /**
   * issue 载体账本（M4d，05§2.5）：列出 bot 的 issue，只保留完成标记（run / finding）内 commit 与 {@code commitSha}
   * 一致者——issue 载体本身不带 PR 号，head sha 即键（05§1.3）。
   */
  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    return GitLabLedgerSupport.parseFiltered(
        listBotIssues(repo, null),
        markerParser,
        outcome -> GitLabLedgerSupport.marksCommit(outcome, commitSha));
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    String login = botUsername(repo);
    List<LedgerComment> out = new ArrayList<>();
    String page = "1";
    boolean done = false;
    for (int i = 0; i <= MAX_PAGES && !done; i++) {
      String query =
          "/issues?state=all&order_by=updated_at&sort=desc&author_username="
              + GitLabPayloadSupport.urlEncode(login)
              + (since == null
                  ? ""
                  : "&updated_after=" + GitLabPayloadSupport.urlEncode(since.toString()));
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(query, page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证列表完整: " + query);
        }
        done = true;
      } else if (!root.isArray() || root.isEmpty()) {
        done = true;
      } else {
        boolean reachedOlder = collectIssuesPage(root, login, since, out);
        if (root.size() < PAGE_SIZE || reachedOlder) {
          done = true;
        } else {
          page = nextPage(p, page);
          done = page == null; // 异常分页头：页号无法递增，按无下一页处理
        }
      }
    }
    return out;
  }

  /**
   * 处理一页 issue：窗口外条目跳过（整页越过下界则停止翻页），只收 bot 发布且 iid 合法的条目。
   *
   * @param root 页数组
   * @param login bot 用户名
   * @param since 窗口下界（null = 不限）
   * @param out 输出
   * @return true = 本页出现早于窗口的条目（后续页只会更早）
   */
  private static boolean collectIssuesPage(
      JsonNode root, String login, Instant since, List<LedgerComment> out) {
    boolean reachedOlder = false;
    for (JsonNode issue : root) {
      Instant updated = DispositionSupport.instantOrNull(issue.path("updated_at"));
      if (since != null && (updated == null || updated.isBefore(since))) {
        if (updated != null) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
        }
      } else if (login.equals(issue.path("author").path("username").asText(""))
          && issue.path("iid").asLong(0) > 0) {
        out.add(
            new LedgerComment(
                issue.path("iid").asLong(0),
                LedgerComment.KIND_ISSUE,
                login,
                issue.path("description").asText(""),
                DispositionSupport.instantOrEpoch(issue.path("created_at")),
                null));
      }
    }
    return reachedOlder;
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    List<PullRequestRef> out = new ArrayList<>();
    String page = "1";
    boolean done = false;
    for (int i = 0; i <= MAX_PAGES && !done; i++) {
      GitLabHttpTransport.Page p =
          transport.getPage(
              repo, withPage("/merge_requests?state=all&order_by=updated_at&sort=desc", page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证列表完整");
        }
        done = true;
      } else if (!root.isArray() || root.isEmpty()) {
        done = true;
      } else {
        boolean reachedOlder = collectMergeRequestsPage(repo, root, since, out);
        if (root.size() < PAGE_SIZE || reachedOlder) {
          done = true;
        } else {
          page = nextPage(p, page);
          done = page == null; // 异常分页头：页号无法递增，按无下一页处理
        }
      }
    }
    return out;
  }

  /**
   * 处理一页 MR：窗口外条目跳过（整页越过下界则停止翻页），缺 head/base sha 的 MR 如实告警跳过。
   *
   * @param repo 仓库
   * @param root 页数组
   * @param since 窗口下界（null = 不限）
   * @param out 输出
   * @return true = 本页出现早于窗口的条目（后续页只会更早）
   */
  private boolean collectMergeRequestsPage(
      RepoRef repo, JsonNode root, Instant since, List<PullRequestRef> out) {
    boolean reachedOlder = false;
    for (JsonNode mr : root) {
      Instant updated = DispositionSupport.instantOrNull(mr.path("updated_at"));
      if (since != null && (updated == null || updated.isBefore(since))) {
        if (updated != null) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
        }
        continue;
      }
      int iid = mr.path("iid").asInt(0);
      if (iid > 0) {
        appendMergeRequest(repo, mr, iid, updated, out);
      }
    }
    return reachedOlder;
  }

  /**
   * 追加单条 MR（缺失 sha 时补取详情；仍缺则告警跳过）。
   *
   * @param repo 仓库
   * @param mr MR 摘要节点
   * @param iid MR iid
   * @param updated 更新时间
   * @param out 输出
   */
  private void appendMergeRequest(
      RepoRef repo, JsonNode mr, int iid, Instant updated, List<PullRequestRef> out) {
    String head = GitLabJsonSupport.textOrNull(mr, "sha");
    String base = GitLabJsonSupport.textOrNull(mr.path("diff_refs"), "base_sha");
    if (head == null || base == null) {
      JsonNode full = fetchMergeRequest(repo, iid);
      if (head == null) {
        head = GitLabJsonSupport.textOrNull(full.path("diff_refs"), "head_sha");
      }
      if (base == null) {
        base = GitLabJsonSupport.textOrNull(full.path("diff_refs"), "base_sha");
      }
    }
    if (head == null || base == null) {
      LOG.warn("gitlab MR !{} has no head/base sha; skipped from window", iid);
      return;
    }
    out.add(
        new PullRequestRef(
            iid,
            head,
            GitLabJsonSupport.textOrNull(mr, "merge_commit_sha"),
            "opened".equals(mr.path("state").asText(""))
                ? PullRequestRef.STATE_OPEN
                : PullRequestRef.STATE_CLOSED,
            updated == null ? Instant.EPOCH : updated,
            DispositionSupport.instantOrNull(mr.path("merged_at")),
            base));
  }

  private static String nextPage(GitLabHttpTransport.Page page, String currentPage) {
    // GitLab.com 可能省略部分分页头；满页且无 X-Next-Page 时按 offset 递增兜底
    return page.nextPage().isBlank() ? incrementPageOrNull(currentPage) : page.nextPage();
  }

  /** 页号递增兜底：非数字或超出 int 范围（异常分页头）按无下一页处理，返回 {@code null}，不抛 {@link NumberFormatException}。 */
  static String incrementPageOrNull(String currentPage) {
    try {
      return String.valueOf(Integer.parseInt(currentPage) + 1);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private JsonNode fetchMergeRequest(RepoRef repo, int iid) {
    return transport.getJson(repo, "/merge_requests/" + iid);
  }

  /**
   * 处置信号（06§1.1）：人类对 bot discussion 的 reply 标签 + bot Summary note 的 👍/👎。
   *
   * <p>GitLab reply 没有 `in_reply_to_id`，线程关系由 discussion 表达；reaction 走 note 的 award_emoji
   * （`thumbsup`/`thumbsdown`）。discussion `resolved` 虽在 REST 可得，但 DispositionSignal 模型无该类别，
   * 本轮不采（ADR-0011 决策 10）。
   */
  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    String login = botUsername(repo);
    List<JsonNode> discussions =
        getPagedArray(repo, "/merge_requests/" + prNumber + "/discussions");
    List<DispositionSignal> out = new ArrayList<>();
    collectReplySignals(discussions, login, out);
    collectReactionSignals(repo, prNumber, discussions, login, out);
    return out.toArray(new DispositionSignal[0]);
  }

  /**
   * 采集人类对 bot discussion 的 reply 标签（06§1.1；线程父 = 该 discussion 首条 bot note）。
   *
   * @param discussions discussion 列表
   * @param login bot 用户名
   * @param out 信号输出
   */
  private static void collectReplySignals(
      List<JsonNode> discussions, String login, List<DispositionSignal> out) {
    for (JsonNode discussion : discussions) {
      Long parentId = null;
      for (JsonNode note : discussion.path("notes")) {
        long noteId = note.path("id").asLong(0);
        if (note.path("system").asBoolean(false) || noteId <= 0) {
          continue;
        }
        String author = note.path("author").path("username").asText("");
        if (login.equals(author)) {
          if (parentId == null) {
            parentId = noteId;
          }
        } else if (parentId != null) {
          // 人类自建线程（parentId == null）不是处置信号
          appendReplySignal(note, parentId, author, out);
        }
      }
    }
  }

  /**
   * 追加单条 reply 标签信号（无标签回复不改变处置分类，ignored 口径据此保持噪声语义）。
   *
   * @param note 人类 note
   * @param parentId 线程父 note id
   * @param author 作者
   * @param out 信号输出
   */
  private static void appendReplySignal(
      JsonNode note, long parentId, String author, List<DispositionSignal> out) {
    String body = note.path("body").asText("");
    String tag = DispositionSupport.replyTag(body);
    if (tag == null) {
      return;
    }
    out.add(
        new DispositionSignal(
            DispositionSignal.KIND_REPLY_TAG,
            parentId,
            DispositionSupport.instantOrEpoch(note.path("created_at")),
            tag,
            author,
            DispositionSupport.excerpt(body)));
  }

  /**
   * 采集 bot Summary note 的 👍/👎 reaction（06§1.1；inline note 不参与 run 满意度）。
   *
   * @param repo 仓库
   * @param prNumber PR 号
   * @param discussions discussion 列表
   * @param login bot 用户名
   * @param out 信号输出
   */
  private void collectReactionSignals(
      RepoRef repo,
      int prNumber,
      List<JsonNode> discussions,
      String login,
      List<DispositionSignal> out) {
    for (JsonNode discussion : discussions) {
      for (JsonNode note : discussion.path("notes")) {
        long noteId = note.path("id").asLong(0);
        if (note.path("system").asBoolean(false)
            || !login.equals(note.path("author").path("username").asText(""))
            || note.path("position").isObject()
            || noteId <= 0) {
          continue;
        }
        collectNoteReactions(repo, prNumber, noteId, out);
      }
    }
  }

  /**
   * 采集单条 note 的 award_emoji 反应信号（只识别 thumbsup/thumbsdown）。
   *
   * @param repo 仓库
   * @param prNumber PR 号
   * @param noteId note id
   * @param out 信号输出
   */
  private void collectNoteReactions(
      RepoRef repo, int prNumber, long noteId, List<DispositionSignal> out) {
    for (JsonNode award :
        getPagedArray(repo, "/merge_requests/" + prNumber + "/notes/" + noteId + "/award_emoji")) {
      String content = reactionContent(award.path("name").asText(""));
      if (content == null) {
        continue;
      }
      out.add(
          new DispositionSignal(
              DispositionSignal.KIND_REACTION,
              noteId,
              DispositionSupport.instantOrEpoch(award.path("created_at")),
              content,
              award.path("user").path("username").asText(""),
              ""));
    }
  }

  /**
   * award_emoji 名称 → 处置信号内容（未知反应返回 null）。
   *
   * @param name award_emoji 名称
   * @return 信号内容或 null
   */
  private static String reactionContent(String name) {
    if ("thumbsup".equals(name)) {
      return DispositionSignal.REACTION_UP;
    }
    if ("thumbsdown".equals(name)) {
      return DispositionSignal.REACTION_DOWN;
    }
    return null;
  }

  List<JsonNode> getPagedArray(RepoRef repo, String pathAndQuery) {
    List<JsonNode> out = new ArrayList<>();
    String page = "1";
    boolean exhausted = false;
    // 多探一页：恰好 100 个满页（10k 条）时，第 101 页为空必须视为正常结束，而不是触顶
    for (int i = 0; i <= MAX_PAGES && !exhausted; i++) {
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(pathAndQuery, page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        // 探页：恰好 MAX_PAGES 个满页后第 101 页为空才算正常结束；有数据即超过上限
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证结果完整: " + pathAndQuery);
        }
        exhausted = true;
      } else if (!root.isArray() || root.isEmpty()) {
        exhausted = true;
      } else {
        root.forEach(out::add);
        String next = nextPageOrNull(p, root, page);
        if (next == null) {
          exhausted = true; // 无下一页（短页）或异常分页头：按正常结束处理
        } else {
          page = next;
        }
      }
    }
    if (!exhausted) {
      // 触顶仍是满页 = 还有数据未读：返回部分结果会让文件索引/账本"看起来完整"
      throw new CraIntegrationException(
          "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证结果完整: " + pathAndQuery);
    }
    return out;
  }

  /**
   * 计算下一页：优先服务端分页头；缺失时短页视为结束，满页按 offset 递增兜底。
   *
   * @param p 当前页
   * @param root 页数组
   * @param currentPage 当前页号
   * @return 下一页页号；null = 无下一页
   */
  private static String nextPageOrNull(
      GitLabHttpTransport.Page p, JsonNode root, String currentPage) {
    String next = p.nextPage();
    if (!next.isBlank()) {
      return next;
    }
    if (root.size() < PAGE_SIZE) {
      return null;
    }
    return incrementPageOrNull(currentPage);
  }

  private static String withPage(String pathAndQuery, String page) {
    String sep = pathAndQuery.contains("?") ? "&" : "?";
    return pathAndQuery + sep + "per_page=" + PAGE_SIZE + "&page=" + page;
  }

  private String botUsername(RepoRef repo) {
    if (cachedUsername == null) {
      try {
        String username = transport.getAbsoluteJson("/user").path("username").asText("");
        if (!username.isBlank()) {
          cachedUsername = username;
        }
      } catch (CraIntegrationException e) {
        if (!GitLabHttpTransport.isUserUnauthorized(e)) {
          throw e;
        }
        LOG.warn("GET /user unauthorized; using configured bot username fallback if present");
      }
      if (cachedUsername == null) {
        if (botUsernameFallback == null) {
          throw new CraIntegrationException(
              "GitLab bot identity unavailable: GET /user failed and no botUsername fallback"
                  + " (CI job tokens need an explicit bot username)");
        }
        cachedUsername = botUsernameFallback;
      }
    }
    return cachedUsername;
  }
}
