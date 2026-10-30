package org.cra.git.gitlab.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerMarkerParser;
import org.cra.common.git.LedgerMarkerParser.MarkerParseResult;
import org.cra.common.git.LedgerMarkerParser.ParseOutcome;
import org.cra.common.git.LedgerRunMarker;

/**
 * 账本标记解析与 commit comment 合成 id 的纯辅助（05§1.3 规则原样移植，ADR-0011 决策 7）。
 *
 * <p>标记解析下沉 cra-common 共享（ADR-0016）：本类只保留 GitLab 特有的评论→账本映射与合成 id。
 */
final class GitLabLedgerSupport {

  private GitLabLedgerSupport() {}

  static CommentLedger parse(List<LedgerComment> comments, LedgerMarkerParser parser) {
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    int suspects = 0;
    for (LedgerComment c : comments) {
      ParseOutcome outcome = parser.parse(c.body());
      suspects += outcome.suspects().size();
      if (outcome.marker().isEmpty()) {
        continue;
      }
      MarkerParseResult parsed = outcome.marker().get();
      if (parsed instanceof MarkerParseResult.FindingMark f) {
        findings.add(
            new LedgerFindingMarker(
                f.fingerprint(),
                f.file(),
                f.line(),
                f.category(),
                f.findingId(),
                f.runId(),
                f.commitSha(),
                c.id()));
      } else if (parsed instanceof MarkerParseResult.RunMark r) {
        runs.add(
            new LedgerRunMarker(
                r.runId(), r.commitSha(), r.status(), r.findings(), r.suppressed(), c.id()));
      }
    }
    return new CommentLedger(comments, findings, runs, suspects);
  }

  static boolean marksCommit(ParseOutcome outcome, String commitSha) {
    if (outcome.marker().isEmpty()) {
      return false;
    }
    MarkerParseResult parsed = outcome.marker().get();
    if (parsed instanceof MarkerParseResult.RunMark r) {
      return r.commitSha().equalsIgnoreCase(commitSha);
    }
    return ((MarkerParseResult.FindingMark) parsed).commitSha().equalsIgnoreCase(commitSha);
  }

  /**
   * GitLab commit comment 无平台 id（ADR-0011 决策 7）：由 `sha + 正文` 生成稳定的本地合成 id。
   *
   * <p>发布侧与回放侧使用同一函数，因此完成标记的 commentId 可在同 commit 重跑时命中；该 id 不是平台标识， 不可用于编辑/删除评论。
   *
   * @param commitSha commit sha
   * @param note 评论正文
   * @return 正整数合成 id
   */
  static long syntheticCommitCommentId(String commitSha, String note) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((commitSha + "\n" + note).getBytes(StandardCharsets.UTF_8));
      long id = Long.parseLong(HexFormat.of().formatHex(digest).substring(0, 15), 16);
      return id == 0 ? 1L : id;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
