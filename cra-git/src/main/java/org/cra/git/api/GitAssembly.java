package org.cra.git.api;

import java.nio.file.Path;

/** cra-git 装配工厂（10§4：组合根只调工厂触达 internal 实现）。 */
public final class GitAssembly {

  private GitAssembly() {}

  /**
   * 创建本地仓库上下文（jgit blame，05§2.1）。目录不是 git 仓库时 localRepoRoot() 为空—— 合法形态，pre-existing 检测降级为不标注 +
   * Summary 声明（05§2.1）。
   *
   * @param workspaceRoot 候选仓库根（CI checkout 目录或本地工作区；null 视为不可得）
   * @return LocalRepoContext
   */
  public static LocalRepoContext createLocalRepoContext(Path workspaceRoot) {
    return new org.cra.git.internal.JgitLocalRepoContext(workspaceRoot);
  }
}
