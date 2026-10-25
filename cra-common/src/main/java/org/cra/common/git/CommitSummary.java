package org.cra.common.git;

import java.util.Objects;

/**
 * 变更集内单条 commit 摘要（brief 的 commit_messages 注入用，04§4.1）。
 *
 * @param sha commit 短/长 sha
 * @param message commit message 全文
 */
public record CommitSummary(String sha, String message) {

  public CommitSummary {
    Objects.requireNonNull(sha, "sha");
    if (sha.isBlank()) {
      throw new IllegalArgumentException("sha must be non-blank");
    }
    message = message == null ? "" : message;
  }
}
