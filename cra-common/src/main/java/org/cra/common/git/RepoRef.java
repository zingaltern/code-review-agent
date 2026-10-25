package org.cra.common.git;

import java.util.Objects;

/**
 * 仓库引用（05§2.1 SPI 入参）。
 *
 * @param owner 仓库属主（个人或组织）
 * @param repo 仓库名
 */
public record RepoRef(String owner, String repo) {

  public RepoRef {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(repo, "repo");
    if (owner.isBlank() || repo.isBlank()) {
      throw new IllegalArgumentException("owner/repo must be non-blank");
    }
  }

  /**
   * 解析 "owner/repo" 形式（如 GITHUB_REPOSITORY 环境变量值）。
   *
   * @param fullName "owner/repo"
   * @return RepoRef
   */
  public static RepoRef parse(String fullName) {
    Objects.requireNonNull(fullName, "fullName");
    String[] parts = fullName.split("/");
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw new IllegalArgumentException("expected owner/repo, got: " + fullName);
    }
    return new RepoRef(parts[0], parts[1]);
  }
}
