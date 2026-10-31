package org.cra.store.internal;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewKey;
import org.cra.common.run.ReviewMode;
import org.cra.store.api.ReviewLedger;

/**
 * 无 PR 形态的 GitHub 载体账本（M4c push = commit comment；M4d 夜间 = issue，05§1.3 键控扩展）。
 *
 * <p>与 {@link GitHubCommentLedger} 的分工：PR 形态按 {@code (repo, pr)} 组织且在 PR 内合并 inline 与 issue 两端点；无
 * PR 形态的载体本身就是账本作用域——push 的 commit comment 端点按 commit 作用域，夜间 issue 按标记内 commit 过滤。二者都只需
 * 一条载体评论级完成标记（没有任何 finding 级标记依赖：载体是单条原子发布，不存在"发布 3/8 条后崩溃"的半截状态，02§4.1）。
 *
 * <p>写侧保持 {@link ReviewLedger} 的 default 无操作：标记随载体正文发布，账本即评论本身（ADR-0001）。
 */
public final class GitHubCarrierLedger implements ReviewLedger {

  private final CommentSource source;
  private final RepoRef repo;
  private final ReviewMode mode;

  /**
   * @param source 账本取数端口（构造器注入，ADR-0005）
   * @param repo 绑定仓库
   * @param mode 载体形态（COMMIT | ISSUE）
   */
  public GitHubCarrierLedger(CommentSource source, RepoRef repo, ReviewMode mode) {
    if (source == null || repo == null || mode == null) {
      throw new IllegalArgumentException("source/repo/mode 不能为空");
    }
    if (mode == ReviewMode.PULL_REQUEST) {
      throw new IllegalArgumentException("PR 形态请使用 GitHubCommentLedger（05§2.2）");
    }
    this.source = source;
    this.repo = repo;
    this.mode = mode;
  }

  @Override
  public Optional<LedgerRunMarker> findCompletion(int prNumber, String commitSha) {
    // int 形态不带 PR 语义：无 PR 载体只按 commit 作用域取数（05§1.3）
    return fetchBySha(commitSha).runMarkers().stream()
        .filter(LedgerRunMarker::isCompletion)
        .filter(m -> m.commitSha().equalsIgnoreCase(commitSha))
        .findFirst();
  }

  @Override
  public Optional<LedgerRunMarker> findCompletion(ReviewKey key) {
    // 同 commit 重跑时取首条完成标记即可（闸语义只判存在性，05§1.3）
    return fetch(key).runMarkers().stream()
        .filter(LedgerRunMarker::isCompletion)
        .filter(m -> m.commitSha().equalsIgnoreCase(key.commitSha()))
        .findFirst();
  }

  @Override
  public List<LedgerFindingMarker> publishedFindings(int prNumber) {
    // 无 PR 载体不含 finding 级标记，且 int 形态无法表达 commit 作用域——按契约返回空
    return List.of();
  }

  @Override
  public List<LedgerFindingMarker> publishedFindings(ReviewKey key) {
    // 无 PR 载体不含 finding 级标记（05§1.3：一条评论只承载一个末位标记）；保留过滤以便未来扩展不误用
    return fetch(key).findingMarkers().stream()
        .filter(m -> m.commitSha().equalsIgnoreCase(key.commitSha()))
        .toList();
  }

  @Override
  public List<LedgerRunMarker> completedRuns(int prNumber) {
    // int 形态无法表达 commit 作用域；无 PR 形态的收敛轮次以单 commit 载体为准，返回空
    return List.of();
  }

  @Override
  public List<LedgerRunMarker> completedRuns(ReviewKey key) {
    return LedgerMarkerOrder.completionMarkers(fetch(key)).stream()
        .filter(m -> m.commitSha().equalsIgnoreCase(key.commitSha()))
        .toList();
  }

  @Override
  public int suspectMarkerCount(int prNumber) {
    // int 形态无法表达 commit 作用域；无 PR 载体的可疑计数只走键控重载
    return 0;
  }

  @Override
  public int suspectMarkerCount(ReviewKey key) {
    return fetch(key).suspectCount();
  }

  @Override
  public Optional<LedgerComment> comment(int prNumber, long commentId) {
    return Optional.empty();
  }

  @Override
  public Optional<LedgerComment> comment(ReviewKey key, long commentId) {
    return fetch(key).comments().stream().filter(c -> c.id() == commentId).findFirst();
  }

  private CommentLedger fetch(ReviewKey key) {
    if (key.mode() != mode) {
      throw new IllegalArgumentException(
          "账本形态不匹配：ledger=" + mode + " key=" + key.mode() + "（组合根装配错误）");
    }
    if (!repo.owner().equalsIgnoreCase(key.repoOwner())
        || !repo.repo().equalsIgnoreCase(key.repoName())) {
      throw new IllegalArgumentException(
          "账本仓库不匹配：ledger=" + repo + " key=" + key.repoOwner() + "/" + key.repoName());
    }
    return fetchBySha(key.commitSha());
  }

  private CommentLedger fetchBySha(String commitSha) {
    return switch (mode) {
      case COMMIT -> source.fetchCommitBotComments(repo, commitSha);
      case ISSUE -> source.fetchIssueBotComments(repo, commitSha);
      case PULL_REQUEST -> throw new IllegalStateException("不可达：构造器已拒绝 PR 形态");
    };
  }
}
