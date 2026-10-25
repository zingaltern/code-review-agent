package org.cra.eval.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.exception.CraException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.common.text.Texts;
import org.cra.git.api.GitPlatformAdapter;

/**
 * 真实 GitHub 账本取数（T3.4）：PR 列表与账本/处置信号走 {@link CommentSource} 端口（ADR-0005，构造器注入）， 后续 commit 行变更走
 * {@link GitPlatformAdapter#fetchDiff}（compare API）。resolve 状态不采（GraphQL-only，05§2.3）。
 *
 * <p>只对已合并 PR 计算行变更：未合并 PR 的 head 仍会移动，"fixed/ignored" 尚无定论（06§1.1，合并事件才回填）。
 */
final class GitHubWindowSource implements ReportWindowSource {

  private final CommentSource comments;
  private final GitPlatformAdapter adapter;

  GitHubWindowSource(CommentSource comments, GitPlatformAdapter adapter) {
    this.comments = comments;
    this.adapter = adapter;
  }

  @Override
  public String label() {
    return "github";
  }

  @Override
  public WindowData.Window load(RepoRef repo, Instant since) {
    List<String> notes = new ArrayList<>();
    List<PullRequestRef> pulls = comments.listPullRequests(repo, since);
    if (pulls.isEmpty()) {
      notes.add("窗口内无 PR（或平台不支持 PR 列表）——处置与满意度维度无数据");
    }
    List<WindowData.PrWindow> prs = new ArrayList<>();
    for (PullRequestRef pull : pulls) {
      CommentLedger ledger = comments.fetchBotComments(repo, pull.number());
      List<DispositionSignal> signals =
          Arrays.asList(comments.fetchDispositionSignals(repo, pull.number()));
      Map<String, WindowData.TouchFacts> touches = new HashMap<>();
      if (pull.merged()) {
        Set<String> commits = new LinkedHashSet<>();
        ledger
            .findingMarkers()
            .forEach(m -> commits.add(m.commitSha() == null ? "" : m.commitSha()));
        for (String commit : commits) {
          if (commit.isBlank()) {
            continue;
          }
          if (commit.equalsIgnoreCase(pull.headSha())) {
            touches.put(commit, WindowData.TouchFacts.EMPTY);
            continue;
          }
          try {
            touches.put(commit, touchFacts(adapter.fetchDiff(repo, commit, pull.headSha())));
          } catch (CraException e) {
            // 单个 commit 的 compare 不可得：该 finding 的 fixed 初判标为不可判定（不猜"未改"）
            notes.add(
                "PR #"
                    + pull.number()
                    + " commit "
                    + abbreviate(commit)
                    + "..head 的 compare 不可得（"
                    + e.getMessage()
                    + "）——该评论的 fixed 初判不可用");
          }
        }
      }
      prs.add(new WindowData.PrWindow(pull, ledger, signals, touches));
    }
    return new WindowData.Window(repo, label(), prs, List.of(), List.of(), notes);
  }

  private static WindowData.TouchFacts touchFacts(DiffResult diff) {
    Set<String> files = new HashSet<>();
    Set<WindowData.LineKey> lines = new HashSet<>();
    for (DiffFile file : diff.files()) {
      boolean renameOrDelete =
          "renamed".equals(file.status())
              || "removed".equals(file.status())
              || file.previousPath() != null;
      if (renameOrDelete || file.patch() == null) {
        // 重命名/删除/二进制：行号映射不可靠，按整文件"涉及"处理
        files.add(file.path());
        if (file.previousPath() != null) {
          files.add(file.previousPath());
        }
        continue;
      }
      for (DiffHunk hunk : file.hunks()) {
        int from = hunk.oldStart();
        int count = Math.max(hunk.oldLines(), 1);
        for (int line = from; line < from + count; line++) {
          lines.add(new WindowData.LineKey(file.path(), line));
        }
      }
    }
    return new WindowData.TouchFacts(files, lines);
  }

  private static String abbreviate(String sha) {
    return Texts.shortSha(sha, 8, "");
  }
}
