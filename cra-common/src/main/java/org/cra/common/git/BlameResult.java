package org.cra.common.git;

import java.util.Objects;

/**
 * blame 结果（05§2.1 LocalRepoContext.blame 返回值；pre-existing 复核用，M1 落地）。
 *
 * @param path 文件路径
 * @param line 行号
 * @param commitSha 该行最后修改的 commit
 * @param author 该 commit 作者
 */
public record BlameResult(String path, int line, String commitSha, String author) {

  public BlameResult {
    Objects.requireNonNull(path, "path");
    if (path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    if (line <= 0) {
      throw new IllegalArgumentException("line must be positive: " + line);
    }
    Objects.requireNonNull(commitSha, "commitSha");
    if (commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
    author = author == null ? "" : author;
  }
}
