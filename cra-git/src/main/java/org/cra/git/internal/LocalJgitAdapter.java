package org.cra.git.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.text.Texts;
import org.cra.git.api.LocalGitAdapter;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * 本地 jgit 适配器（02§5 本地形态 + 02§7 映射收回条款：本地模式 diff 不走平台 API，jgit 直读仓库； T2.10）。patch 产出与 GitHub compare
 * 同构 （unified diff，{@link DiffHunk#parseAll} 共享解析），下游 规划/锚定/指纹零改动。目录非 git 仓库在工厂期失败（组装期拒绝）。
 *
 * <p>发布语义（02§5）：本地形态无平台回帖，postReview 为 no-op 确认——Summary comment id 固定 0， 编排器据此把完成标记/finding 指纹写入
 * SQLite 账本（ReviewLedger 可持久化形态的写侧）；updateSummaryComment 沿用 SPI
 * default（UnsupportedOperationException，本地收尾路径不触达）。
 */
public final class LocalJgitAdapter implements LocalGitAdapter {

  private final Repository repository;

  /**
   * @param workspaceRoot 仓库工作树根（须含 .git）
   * @throws CraIntegrationException 目录不是 git 仓库
   */
  public LocalJgitAdapter(Path workspaceRoot) {
    // jgit Repository 打开是惰性的：build() 对无 .git 目录不报错，必须显式校验（.git 为目录或文件皆可，
    // 文件形态 = worktree/submodule）
    Path gitDir = workspaceRoot.resolve(".git");
    if (!Files.isDirectory(gitDir) && !Files.isRegularFile(gitDir)) {
      throw new CraIntegrationException("目录不是 git 仓库（缺 .git）: " + workspaceRoot);
    }
    try {
      this.repository = new FileRepositoryBuilder().setWorkTree(workspaceRoot.toFile()).build();
    } catch (IOException e) {
      throw new CraIntegrationException("本地仓库打开失败: " + workspaceRoot, e);
    }
  }

  @Override
  public String resolve(String refOrSha) {
    try {
      ObjectId id = repository.resolve(refOrSha);
      if (id == null) {
        throw new CraIntegrationException("ref/sha 不可解析: " + refOrSha);
      }
      return id.getName();
    } catch (IOException e) {
      throw new CraIntegrationException("ref/sha 解析失败: " + refOrSha, e);
    }
  }

  @Override
  public String headSha() {
    return resolve("HEAD");
  }

  @Override
  public Optional<String> remoteOriginOwnerSlashName() {
    String url = repository.getConfig().getString("remote", "origin", "url");
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    String cleaned = normalizeRemoteUrl(url);
    int slash = cleaned.lastIndexOf('/');
    if (slash <= 0 || slash == cleaned.length() - 1) {
      return Optional.empty();
    }
    String owner = cleaned.substring(0, slash);
    int ownerSlash = owner.lastIndexOf('/');
    if (ownerSlash >= 0) {
      owner = owner.substring(ownerSlash + 1);
    }
    owner = owner.replaceFirst("^[^:]*:", ""); // scp 形态 git@github.com:owner/name
    if (owner.isBlank() || cleaned.substring(slash + 1).isBlank()) {
      return Optional.empty();
    }
    return Optional.of(owner + "/" + cleaned.substring(slash + 1));
  }

  /** 去掉 query/fragment、尾斜杠与 `.git` 后缀（remote url 的常见书写变体）。 */
  private static String normalizeRemoteUrl(String url) {
    String cleaned = url.strip();
    int end = cleaned.length();
    int query = cleaned.indexOf('?');
    int fragment = cleaned.indexOf('#');
    if (query >= 0) {
      end = Math.min(end, query);
    }
    if (fragment >= 0) {
      end = Math.min(end, fragment);
    }
    cleaned = cleaned.substring(0, end);
    while (cleaned.endsWith("/")) {
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    }
    return cleaned.replaceFirst("\\.git$", "");
  }

  @Override
  public DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha) {
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit baseCommit = parseCommit(walk, resolve(baseSha));
      RevCommit headCommit = parseCommit(walk, resolve(headSha));
      // 与 GitHub compare（三点语义）同构：diff 基线 = merge-base，而不是 base 分支 tip——
      // 否则分支落后时会把目标分支的新提交以反向 diff 算进评审
      String mergeBaseSha = mergeBase(baseCommit, headCommit);
      if (mergeBaseSha == null) {
        throw new CraIntegrationException(
            "本地 diff 无共同祖先: " + shortSha(baseSha) + ".." + shortSha(headSha));
      }
      RevCommit diffBase = parseCommit(walk, mergeBaseSha);
      List<DiffFile> files = new ArrayList<>();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DiffFormatter formatter = new DiffFormatter(buffer)) {
        formatter.setRepository(repository);
        formatter.setDetectRenames(true);
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        for (DiffEntry entry : formatter.scan(toTree(walk, diffBase), toTree(walk, headCommit))) {
          files.add(toDiffFile(formatter, buffer, entry));
        }
      }
      return new DiffResult(
          diffBase.getName(),
          headCommit.getName(),
          files,
          commitsBetween(walk, diffBase, headCommit));
    } catch (IOException e) {
      throw new CraIntegrationException(
          "本地 diff 失败: " + shortSha(baseSha) + ".." + shortSha(headSha), e);
    }
  }

  /** merge-base（三点 diff 基线）；无共同祖先返回 null。 */
  private String mergeBase(RevCommit base, RevCommit head) {
    try (RevWalk walk = new RevWalk(repository)) {
      walk.setRevFilter(RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(base));
      walk.markStart(walk.parseCommit(head));
      RevCommit first = walk.next();
      return first == null ? null : first.getName();
    } catch (IOException e) {
      throw new CraIntegrationException("merge-base 计算失败", e);
    }
  }

  @Override
  public FileContent readFile(RepoRef repo, String sha, String path) {
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit commit = parseCommit(walk, resolve(sha));
      try (TreeWalk tw = TreeWalk.forPath(repository, path, commit.getTree())) {
        if (tw == null) {
          throw new CraIntegrationException("文件不存在于 " + shortSha(sha) + ": " + path);
        }
        byte[] bytes = repository.open(tw.getObjectId(0)).getBytes();
        try {
          // 严格 UTF-8（Texts，ADR-0016 收敛）：失败降级 binary 语义（text=null），不静默替换
          return new FileContent(path, Texts.strictUtf8(bytes), false);
        } catch (CharacterCodingException e) {
          return new FileContent(path, null, true);
        }
      }
    } catch (IOException e) {
      throw new CraIntegrationException("本地读文件失败: " + path + " @" + shortSha(sha), e);
    }
  }

  @Override
  public List<String> listFiles(RepoRef repo, String sha, String pathGlob) {
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit commit = parseCommit(walk, resolve(sha));
      // PathGlobs.compile 每次调用新编译（ADR-0016 单一实现），树遍历内复用同一实例
      Pattern glob = pathGlob == null || pathGlob.isBlank() ? null : PathGlobs.compile(pathGlob);
      List<String> out = new ArrayList<>();
      try (TreeWalk tw = new TreeWalk(repository)) {
        tw.addTree(commit.getTree());
        tw.setRecursive(true);
        while (tw.next()) {
          String path = tw.getPathString();
          if (glob == null || glob.matcher(path).matches()) {
            out.add(path);
          }
        }
      }
      return out;
    } catch (IOException e) {
      throw new CraIntegrationException("本地列文件失败 @" + shortSha(sha), e);
    }
  }

  @Override
  public PrReviewResult postReview(PostReviewCommand cmd) {
    // 本地形态无平台回帖：逐条 PUBLISHED 确认（commentId 空 → 账本落 0），Summary comment id 固定 0
    List<ReviewItemResult> items =
        cmd.inlineComments().stream()
            .map(
                c ->
                    new ReviewItemResult(
                        c.findingId(),
                        c.path(),
                        c.line(),
                        PublishStatus.PUBLISHED,
                        AnchorStatus.INLINE,
                        null,
                        ""))
            .toList();
    return new PrReviewResult(cmd.prNumber(), Optional.of("local"), Optional.of(0L), items);
  }

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    // 本地形态账本 = SQLite（组合根装配 SqliteLedger），适配器不承载评论取数
    return new CommentLedger(List.of(), List.of(), List.of(), 0);
  }

  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    return new DispositionSignal[0];
  }

  @Override
  public void close() {
    repository.close();
  }

  // ---------- jgit → common 模型映射 ----------

  private RevCommit parseCommit(RevWalk walk, String sha) throws IOException {
    ObjectId id = repository.resolve(sha);
    if (id == null) {
      throw new CraIntegrationException("commit 不可解析: " + sha);
    }
    return walk.parseCommit(id);
  }

  private CanonicalTreeParser toTree(RevWalk walk, RevCommit commit) throws IOException {
    CanonicalTreeParser parser = new CanonicalTreeParser();
    parser.reset(walk.getObjectReader(), commit.getTree());
    return parser;
  }

  private DiffFile toDiffFile(
      DiffFormatter formatter, ByteArrayOutputStream buffer, DiffEntry entry) throws IOException {
    boolean removed = entry.getChangeType() == DiffEntry.ChangeType.DELETE;
    String path = removed ? entry.getOldPath() : entry.getNewPath();
    String previousPath =
        entry.getChangeType() == DiffEntry.ChangeType.RENAME
                || entry.getChangeType() == DiffEntry.ChangeType.COPY
            ? entry.getOldPath()
            : null;
    String status = gitHubStatusOf(entry.getChangeType());
    buffer.reset();
    formatter.format(entry);
    String patch = buffer.toString(StandardCharsets.UTF_8);
    if (isBinaryPatch(patch)) {
      // 与 GitHub compare 语义对齐：二进制文件无 patch 字段（GitHubHttpAdapter 同款 null）
      return new DiffFile(path, previousPath, status, null, List.of());
    }
    return new DiffFile(path, previousPath, status, patch, DiffHunk.parseAll(patch));
  }

  private List<CommitSummary> commitsBetween(RevWalk walk, RevCommit base, RevCommit head)
      throws IOException {
    walk.reset();
    walk.sort(RevSort.COMMIT_TIME_DESC);
    walk.markStart(walk.parseCommit(head.getId()));
    walk.markUninteresting(walk.parseCommit(base.getId()));
    List<CommitSummary> commits = new ArrayList<>();
    for (RevCommit c : walk) {
      commits.add(new CommitSummary(c.getName(), c.getShortMessage()));
    }
    Collections.reverse(commits); // 时间升序 = GitHub compare 顺序
    return commits;
  }

  /** ChangeType → GitHub compare status 词汇（DiffFile.status 消费方按 GitHub 语义解析，02§2）。 */
  private static String gitHubStatusOf(DiffEntry.ChangeType changeType) {
    return switch (changeType) {
      case ADD -> "added";
      case MODIFY -> "modified";
      case DELETE -> "removed";
      case RENAME -> "renamed";
      case COPY -> "copied";
    };
  }

  /** 二进制判定：jgit 对二进制条目不产 hunk，只写占位行（"Binary files … differ" 或 "GIT binary patch"）。 */
  private static boolean isBinaryPatch(String patch) {
    for (String line : patch.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("Binary files") || trimmed.equals("GIT binary patch")) {
        return true;
      }
    }
    return false;
  }

  private static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "");
  }
}
