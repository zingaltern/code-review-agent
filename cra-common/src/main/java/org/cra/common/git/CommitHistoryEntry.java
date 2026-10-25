package org.cra.common.git;

import java.time.Instant;
import java.util.Objects;

/**
 * 路径集近期变更史中的单条 commit（03§3.1 CrossChecker 模式 A 的额外上下文输入 b：上下游路径集在 近 N 周内的 commit 摘要——sha / message
 * / 时间）。
 *
 * <p>commit message 属不可信输入（04§3.3），渲染进 brief 时一律 UNTRUSTED 定界；时间取 committer time（确定性锚点， 相对 head
 * commit 时间回溯，不以运行时钟为准）。
 *
 * @param sha commit sha（完整或缩写，随实现）
 * @param message commit message 全文（渲染侧按行/长度截断）
 * @param committedAt 提交时间（committer time）
 */
public record CommitHistoryEntry(String sha, String message, Instant committedAt) {

  public CommitHistoryEntry {
    Objects.requireNonNull(sha, "sha");
    if (sha.isBlank()) {
      throw new IllegalArgumentException("sha must be non-blank");
    }
    message = message == null ? "" : message;
    Objects.requireNonNull(committedAt, "committedAt");
  }
}
