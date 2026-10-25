package org.cra.git.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.BlameResult;
import org.cra.common.git.CommitHistoryEntry;
import org.cra.common.text.Texts;
import org.cra.git.api.LocalRepoContext;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * jgit blame 实现（05§2.1：GitHub REST/GraphQL 无行级 blame 端点，CI checkout / 本地模式天然有仓库）。 blame 不可得（目录非仓库 /
 * 文件缺失 / 行越界 / 对象缺失）抛 {@link CraIntegrationException}，由消费方逐条降级，不炸穿 run。
 */
public final class JgitLocalRepoContext implements LocalRepoContext {

  /** 历史窗口扫描上限（防御性：8 周窗口内提交密集的仓库不至于拖垮评审；命中上限即停）。 */
  private static final int MAX_HISTORY_SCAN = 500;

  private final Path root;

  /**
   * @param candidateRoot 候选仓库根（null 视为不可得）
   */
  public JgitLocalRepoContext(Path candidateRoot) {
    this.root = candidateRoot;
  }

  @Override
  public Optional<String> localRepoRoot() {
    return root != null && Files.isDirectory(root.resolve(".git"))
        ? Optional.of(root.toString())
        : Optional.empty();
  }

  @Override
  public BlameResult blame(String path, int line, String sha) {
    if (localRepoRoot().isEmpty()) {
      throw new CraIntegrationException("本地仓库不可得（root=" + root + "），blame 降级（05§2.1）");
    }
    try (Repository repository = new FileRepositoryBuilder().setWorkTree(root.toFile()).build()) {
      var commitId = repository.resolve(sha);
      if (commitId == null) {
        throw new CraIntegrationException("commit 不可解析: " + sha);
      }
      org.eclipse.jgit.blame.BlameResult result =
          new BlameCommand(repository).setFilePath(path).setStartCommit(commitId).call();
      if (result == null || line < 1 || result.getResultContents().size() < line) {
        throw new CraIntegrationException(
            "blame 不可得: " + path + " L" + line + " @" + shortSha(sha));
      }
      org.eclipse.jgit.revwalk.RevCommit commit = result.getSourceCommit(line - 1);
      if (commit == null) {
        throw new CraIntegrationException("blame 行无来源 commit: " + path + " L" + line);
      }
      String author = commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getName();
      return new BlameResult(path, line, commit.getName(), author);
    } catch (GitAPIException | java.io.IOException e) {
      throw new CraIntegrationException("jgit blame 失败: " + path + " @" + shortSha(sha), e);
    }
  }

  @Override
  public Optional<String> commitFilePatch(String sha, String path) {
    if (localRepoRoot().isEmpty()) {
      return Optional.empty();
    }
    try (Repository repository = new FileRepositoryBuilder().setWorkTree(root.toFile()).build()) {
      var commitId = repository.resolve(sha);
      if (commitId == null) {
        return Optional.empty();
      }
      org.eclipse.jgit.revwalk.RevCommit commit;
      try (org.eclipse.jgit.revwalk.RevWalk walk =
          new org.eclipse.jgit.revwalk.RevWalk(repository)) {
        commit = walk.parseCommit(commitId);
      }
      if (commit.getParentCount() == 0) {
        return Optional.empty();
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      try (org.eclipse.jgit.diff.DiffFormatter formatter =
          new org.eclipse.jgit.diff.DiffFormatter(out)) {
        formatter.setRepository(repository);
        for (org.eclipse.jgit.diff.DiffEntry entry : formatter.scan(commit.getParent(0), commit)) {
          if (path.equals(entry.getNewPath()) || path.equals(entry.getOldPath())) {
            formatter.format(java.util.List.of(entry));
            return Optional.of(out.toString(java.nio.charset.StandardCharsets.UTF_8));
          }
        }
        return Optional.empty();
      }
    } catch (java.io.IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<CommitHistoryEntry> recentCommits(Set<String> paths, int weeks, String headSha) {
    if (localRepoRoot().isEmpty() || paths == null || paths.isEmpty() || weeks <= 0) {
      return List.of();
    }
    try (Repository repository = new FileRepositoryBuilder().setWorkTree(root.toFile()).build()) {
      var headId = repository.resolve(headSha);
      if (headId == null) {
        return List.of();
      }
      List<CommitHistoryEntry> entries = new ArrayList<>();
      try (RevWalk walk = new RevWalk(repository)) {
        RevCommit head = walk.parseCommit(headId);
        if (head.getCommitterIdent() == null) {
          return List.of();
        }
        Instant since =
            head.getCommitterIdent().getWhenAsInstant().minus(weeks * 7L, ChronoUnit.DAYS);
        walk.sort(RevSort.COMMIT_TIME_DESC);
        walk.markStart(head);
        int scanned = 0;
        org.eclipse.jgit.treewalk.filter.TreeFilter filter =
            PathFilterGroup.createFromStrings(paths);
        for (RevCommit commit : walk) {
          // 时间窗口以 head 的 committer time 为锚（确定性）；COMMIT_TIME_DESC 下可提前结束
          if (commit.getCommitterIdent() == null
              || commit.getCommitterIdent().getWhenAsInstant().isBefore(since)) {
            break;
          }
          if (++scanned > MAX_HISTORY_SCAN) {
            break;
          }
          if (commit.getParentCount() == 0) {
            continue;
          }
          try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setPathFilter(filter);
            if (!formatter.scan(commit.getParent(0), commit).isEmpty()) {
              entries.add(
                  new CommitHistoryEntry(
                      commit.getName(),
                      commit.getFullMessage(),
                      commit.getCommitterIdent().getWhenAsInstant()));
            }
          }
        }
      }
      return List.copyOf(entries);
    } catch (java.io.IOException | RuntimeException e) {
      // best effort（05§2.1 降级语义）：历史不可得不炸 run，消费方如实声明
      return List.of();
    }
  }

  private static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "");
  }
}
