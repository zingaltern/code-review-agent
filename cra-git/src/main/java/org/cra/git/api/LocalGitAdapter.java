package org.cra.git.api;

import java.util.Optional;

/**
 * 本地 git 适配器（M2 本地 pre-PR 模式，02§5 本地形态；T2.10）：在 {@link GitPlatformAdapter} 之上补充 本地仓库解析能力——ref/缩写
 * sha 解析、HEAD 定位、remote origin 身份推导。 diff/readFile/listFiles 走本地 jgit；发布为 no-op 确认（本地无平台回帖，持久化事实源 =
 * SQLite 账本，由编排器经 ReviewLedger 写侧完成）。
 */
public interface LocalGitAdapter extends GitPlatformAdapter, AutoCloseable {

  /** 释放本地仓库句柄（进程生命周期内打开一次；default 空实现便于测试桩）。 */
  @Override
  default void close() {}

  /**
   * 解析 ref 名 / 分支名 / 全或缩写 sha → 完整 commit sha（jgit resolve 语义）。
   *
   * @param refOrSha ref 名或 sha
   * @return 完整 commit sha
   * @throws org.cra.common.exception.CraIntegrationException 不可解析（含 ref 不存在）
   */
  String resolve(String refOrSha);

  /**
   * 当前 HEAD 的完整 commit sha。
   *
   * @return HEAD sha
   * @throws org.cra.common.exception.CraIntegrationException 空仓库（无任何 commit）
   */
  String headSha();

  /**
   * 由 remote origin URL 推导 owner/name（https://github.com/owner/name.git 与
   * git@github.com:owner/name.git 及 同形 URL 均支持；无 origin 或无法推导时为 empty，由调用方落 fallback 身份）。
   *
   * @return owner/name
   */
  Optional<String> remoteOriginOwnerSlashName();
}
