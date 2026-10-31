package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.nio.charset.CharacterCodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.Side;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.text.Texts;
import org.cra.git.api.GitPlatformAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitLab REST v4 实现（ADR-0011 端点映射）：compare diff / repository files / MR discussions（inline）/ MR
 * notes（Summary）/ commit comments / issues / 账本拉取。
 *
 * <p>与 GitHub 实现的结构差异：GitLab 没有"整包 review"对象，inline 逐条建 discussion；diff 锚定需要 base/head/start
 * 三元组；commit comment 无平台 id（由 {@link GitLabLedgerSupport} 生成稳定合成 id）。账本标记格式原样移植 05§1.3，
 * 解析器与处置标签辅助已下沉 cra-common 共享（LedgerMarkerParser / DispositionSupport，ADR-0016；实现模块互不依赖，11§4 第 4
 * 条）；账本/处置/列表读侧见 {@link GitLabCommentReader}。
 */
public class GitLabHttpAdapter implements GitPlatformAdapter, CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabHttpAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final GitLabHttpTransport transport;
  private final GitLabCommentReader commentReader;

  /**
   * @param apiBase API 根（默认 https://gitlab.com/api/v4，测试注入 WireMock 地址）
   * @param token GitLab access token（PRIVATE-TOKEN）
   * @param http HTTP 客户端
   * @param sleeper 退避睡眠（测试注入即时实现）
   * @param botUsernameFallback `/user` 不可读时的 bot 用户名（可空）
   */
  public GitLabHttpAdapter(
      String apiBase,
      String token,
      HttpClient http,
      LongConsumer sleeper,
      String botUsernameFallback) {
    this.transport = new GitLabHttpTransport(apiBase, token, http, sleeper);
    this.commentReader = new GitLabCommentReader(transport, botUsernameFallback);
  }

  // ---------- 读：diff / 文件 / 文件树 ----------

  @Override
  public DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha) {
    JsonNode root =
        transport.getJson(
            repo,
            "/repository/compare?from="
                + GitLabPayloadSupport.urlEncode(baseSha)
                + "&to="
                + GitLabPayloadSupport.urlEncode(headSha));
    if (root.path("compare_timeout").asBoolean(false)) {
      // 部分 diffs 不静默评审（ADR-0011 决策 11）：宁可失败也不产生覆盖声明造假的报告
      throw new CraIntegrationException(
          "GitLab compare timed out for " + baseSha + "..." + headSha + "（partial diffs refused）");
    }
    List<DiffFile> files = new ArrayList<>();
    for (JsonNode d : root.path("diffs")) {
      String path = GitLabJsonSupport.textOrNull(d, "new_path");
      String oldPath = GitLabJsonSupport.textOrNull(d, "old_path");
      String effective = path != null ? path : oldPath;
      if (effective == null) {
        LOG.warn("gitlab compare returned a diff without new_path/old_path; skipped");
        continue;
      }
      boolean renamed = d.path("renamed_file").asBoolean(false);
      String patch =
          d.path("too_large").asBoolean(false) ? null : GitLabJsonSupport.textOrNull(d, "diff");
      files.add(
          new DiffFile(
              effective,
              renamed ? oldPath : null,
              statusOf(d),
              patch,
              patch == null ? List.of() : DiffHunk.parseAll(patch)));
    }
    List<CommitSummary> commits = new ArrayList<>();
    for (JsonNode c : root.path("commits")) {
      String sha = GitLabJsonSupport.textOrNull(c, "id");
      if (sha == null) {
        continue;
      }
      String message = GitLabJsonSupport.textOrNull(c, "message");
      if (message == null) {
        message = GitLabJsonSupport.textOrNull(c, "title");
      }
      commits.add(new CommitSummary(sha, message == null ? "" : message));
    }
    return new DiffResult(baseSha, headSha, files, commits);
  }

  private static String statusOf(JsonNode diff) {
    if (diff.path("new_file").asBoolean(false)) {
      return "added";
    }
    if (diff.path("deleted_file").asBoolean(false)) {
      return "removed";
    }
    if (diff.path("renamed_file").asBoolean(false)) {
      return "renamed";
    }
    return "modified";
  }

  @Override
  public FileContent readFile(RepoRef repo, String sha, String path) {
    byte[] bytes =
        transport.getBytes(
            repo,
            "/repository/files/"
                + GitLabPayloadSupport.encodeFilePath(path)
                + "/raw?ref="
                + GitLabPayloadSupport.urlEncode(sha));
    try {
      return new FileContent(path, Texts.strictUtf8(bytes), false);
    } catch (CharacterCodingException e) {
      return new FileContent(path, null, true);
    }
  }

  @Override
  public List<String> listFiles(RepoRef repo, String sha, String pathGlob) {
    Pattern glob = PathGlobs.compile(pathGlob);
    List<String> out = new ArrayList<>();
    for (JsonNode entry :
        commentReader.getPagedArray(
            repo,
            "/repository/tree?ref=" + GitLabPayloadSupport.urlEncode(sha) + "&recursive=true")) {
      if (!"blob".equals(entry.path("type").asText())) {
        continue;
      }
      String path = entry.path("path").asText("");
      if (!path.isBlank() && glob.matcher(path).matches()) {
        out.add(path);
      }
    }
    return out;
  }

  // ---------- 写：inline discussions → Summary MR note（顺序同 05§2.2） ----------

  @Override
  public PrReviewResult postReview(PostReviewCommand cmd) {
    List<ReviewItemResult> items = new ArrayList<>();
    if (!cmd.inlineComments().isEmpty()) {
      DiffRefs refs = resolveDiffRefs(cmd);
      for (InlineComment c : cmd.inlineComments()) {
        if (refs == null) {
          items.add(fallback(c, "GitLab MR diff_refs unavailable; cannot anchor inline comment"));
          continue;
        }
        try {
          long noteId = createDiscussion(cmd, refs, c);
          items.add(
              new ReviewItemResult(
                  c.findingId(),
                  c.path(),
                  c.line(),
                  PublishStatus.PUBLISHED,
                  AnchorStatus.INLINE,
                  noteId > 0 ? noteId : null,
                  ""));
        } catch (CraIntegrationException e) {
          if (!GitLabHttpTransport.isUnprocessable(e)) {
            throw e; // 非锚定错误不降级，交由 run 级失败处置（02§4.2）
          }
          items.add(fallback(c, e.getMessage()));
        }
      }
    }
    // Summary 最后发：其 DONE 标记是"完整发布"证据；失败 = 无标记 = 重跑走指纹级补发（02§4.1）
    Optional<Long> summaryId = postSummary(cmd);
    // GitLab 无 review 聚合对象：reviewId 显式缺省（ADR-0011 决策 5）
    return new PrReviewResult(cmd.prNumber(), Optional.empty(), summaryId, items);
  }

  private static ReviewItemResult fallback(InlineComment c, String reason) {
    return new ReviewItemResult(
        c.findingId(),
        c.path(),
        c.line(),
        PublishStatus.SUMMARY_FALLBACK,
        AnchorStatus.SUMMARY_FALLBACK,
        null,
        reason);
  }

  private long createDiscussion(PostReviewCommand cmd, DiffRefs refs, InlineComment c) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", c.body());
    ObjectNode position = payload.putObject("position");
    position.put("base_sha", refs.baseSha());
    position.put("head_sha", refs.headSha());
    position.put("start_sha", refs.startSha());
    position.put("position_type", "text");
    position.put("old_path", c.path());
    position.put("new_path", c.path());
    if (c.side() == Side.LEFT) {
      position.put("old_line", c.line());
    } else {
      position.put("new_line", c.line());
    }
    JsonNode response =
        transport.postJson(
            cmd.repoOwner(),
            cmd.repoName(),
            "/merge_requests/" + cmd.prNumber() + "/discussions",
            payload);
    return response.path("notes").path(0).path("id").asLong(0);
  }

  private Optional<Long> postSummary(PostReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", cmd.summaryBody());
    try {
      JsonNode response =
          transport.postJson(
              cmd.repoOwner(),
              cmd.repoName(),
              "/merge_requests/" + cmd.prNumber() + "/notes",
              payload);
      long id = response.path("id").asLong(0);
      return id > 0 ? Optional.of(id) : Optional.empty();
    } catch (CraIntegrationException e) {
      LOG.warn(
          "summary note publish failed; run has no DONE marker, rerun will republish: {}",
          e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void updateSummaryComment(RepoRef repo, int prNumber, long commentId, String body) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", body);
    transport.putJson(
        repo.owner(), repo.repo(), "/merge_requests/" + prNumber + "/notes/" + commentId, payload);
  }

  /**
   * 解析 diff 锚定三元组：优先 MR 的 `diff_refs`；被评审 head 不是当前 head 时从 versions 中找同一 head 的版本；都不可得返回
   * null（逐条降级进 Summary，ADR-0011 决策 5）。
   */
  private DiffRefs resolveDiffRefs(PostReviewCommand cmd) {
    RepoRef repo = new RepoRef(cmd.repoOwner(), cmd.repoName());
    JsonNode mr = transport.getJson(repo, "/merge_requests/" + cmd.prNumber());
    DiffRefs current = toRefs(mr.path("diff_refs"));
    if (current != null && current.headSha().equalsIgnoreCase(cmd.commitId())) {
      return current;
    }
    try {
      for (JsonNode version :
          transport.getJson(repo, "/merge_requests/" + cmd.prNumber() + "/versions")) {
        if (cmd.commitId().equalsIgnoreCase(version.path("head_commit_sha").asText(""))) {
          DiffRefs matched = toVersionRefs(version);
          if (matched != null) {
            return matched;
          }
        }
      }
    } catch (CraIntegrationException e) {
      if (current == null) {
        throw e;
      }
      LOG.warn(
          "gitlab MR versions unavailable; falling back to current diff_refs: {}", e.getMessage());
    }
    if (current != null) {
      LOG.warn(
          "review head {} is not the current MR head {}; anchoring to current diff_refs",
          cmd.commitId(),
          current.headSha());
      return current;
    }
    return null;
  }

  private static DiffRefs toRefs(JsonNode refs) {
    if (refs == null || !refs.isObject()) {
      return null;
    }
    String base = GitLabJsonSupport.textOrNull(refs, "base_sha");
    String head = GitLabJsonSupport.textOrNull(refs, "head_sha");
    String start = GitLabJsonSupport.textOrNull(refs, "start_sha");
    return base == null || head == null || start == null ? null : new DiffRefs(base, head, start);
  }

  private static DiffRefs toVersionRefs(JsonNode version) {
    String base = GitLabJsonSupport.textOrNull(version, "base_commit_sha");
    String head = GitLabJsonSupport.textOrNull(version, "head_commit_sha");
    String start = GitLabJsonSupport.textOrNull(version, "start_commit_sha");
    return base == null || head == null || start == null ? null : new DiffRefs(base, head, start);
  }

  private record DiffRefs(String baseSha, String headSha, String startSha) {}

  // ---------- 写：commit comment（M4c）/ issue（M4d）载体 ----------

  @Override
  public Optional<Long> postCommitReview(PostCommitReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("note", cmd.body());
    transport.postJson(
        cmd.repoOwner(),
        cmd.repoName(),
        "/repository/commits/" + GitLabPayloadSupport.urlEncode(cmd.commitSha()) + "/comments",
        payload);
    // GitLab 不返回 comment id（ADR-0011 决策 7）：返回稳定合成 id，供核心判定"完整发布"
    return Optional.of(GitLabLedgerSupport.syntheticCommitCommentId(cmd.commitSha(), cmd.body()));
  }

  @Override
  public Optional<Long> postIssueReview(PostIssueCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("title", cmd.title());
    payload.put("description", cmd.body());
    if (!cmd.labels().isEmpty()) {
      // GitLab labels 为逗号分隔；缺失标签会被自动创建（ADR-0011 决策 8）
      payload.put("labels", String.join(",", cmd.labels()));
    }
    JsonNode response = transport.postJson(cmd.repoOwner(), cmd.repoName(), "/issues", payload);
    long iid = response.path("iid").asLong(0);
    return iid > 0 ? Optional.of(iid) : Optional.empty();
  }

  // ---------- CommentSource / 账本与处置读侧（实现委托 GitLabCommentReader） ----------

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    return commentReader.fetchBotComments(repo, prNumber);
  }

  @Override
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    return commentReader.fetchCommitBotComments(repo, commitSha);
  }

  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    return commentReader.fetchIssueBotComments(repo, commitSha);
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    return commentReader.listBotIssues(repo, since);
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    return commentReader.listPullRequests(repo, since);
  }

  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    return commentReader.fetchDispositionSignals(repo, prNumber);
  }
}
