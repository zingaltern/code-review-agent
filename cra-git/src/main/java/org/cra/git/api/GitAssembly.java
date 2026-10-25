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

  /**
   * 打开本地 git 适配器（M2 本地 pre-PR 模式，02§5 本地形态；T2.10）。目录不是 git 仓库时 组装期失败（CraIntegrationException）——与
   * {@link #createLocalRepoContext(Path)} 的降级形态不同：本地模式的 diff 数据面必须可得。
   *
   * @param workspaceRoot 仓库工作树根
   * @return LocalGitAdapter（用毕 close）
   */
  public static LocalGitAdapter createLocalAdapter(Path workspaceRoot) {
    return new org.cra.git.internal.LocalJgitAdapter(workspaceRoot);
  }
}
