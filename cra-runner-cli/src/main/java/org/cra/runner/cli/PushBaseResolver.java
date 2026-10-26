package org.cra.runner.cli;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.git.api.GitAssembly;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;

/**
 * push 直推基线解析（M4c，05§2.4）：base = 上一完成 commit。
 *
 * <p>解析顺序：
 *
 * <ol>
 *   <li>显式 {@code --base} / {@code --baseline}：直接使用（可配置基线，首次运行用）；
 *   <li>沿首父链（head 起，最多 {@code --lookback} 步）逐 commit 查 commit comment 账本，命中 DONE/DEGRADED 即取该
 *       commit（"上一完成 commit"的严格语义——被跳过/失败的 push 不产生完成标记，本次 diff 会覆盖它们）；
 *   <li>链上没有任何完成标记（首次评审）：取链尾的仓库初版 commit 作为基线（根 commit 自身变更无父可对比， 不计入；可配置基线优先于该兜底）。
 * </ol>
 *
 * <p>本地仓库不可得（纯 API 模式，无 checkout）时不猜：抛 {@link CraConfigException} 要求显式基线—— 不假装能算出"上一完成 commit"（11§4
 * 第 5 条不可得语义）。
 */
final class PushBaseResolver {

  private PushBaseResolver() {}

  /**
   * 解析 push 形态基线。
   *
   * @param config CLI 配置（reviewMode=COMMIT）
   * @return 基线 sha
   */
  static String resolve(CraConfig config) {
    if (config.baseSha() != null && !config.baseSha().isBlank()) {
      return config.baseSha();
    }
    GitHubComponents components =
        GitHubAssembly.createComponents(config.githubApi(), config.githubToken());
    LocalRepoContext local =
        GitAssembly.createLocalRepoContext(Path.of(config.workspace()).toAbsolutePath());
    return resolve(config, components.commentSource(), local);
  }

  /**
   * 可注入取数端口的解析实现（单测用；生产走 {@link #resolve(CraConfig)}）。
   *
   * @param config CLI 配置
   * @param source 账本取数端口
   * @param local 本地仓库上下文（首父链）
   * @return 基线 sha
   */
  static String resolve(CraConfig config, CommentSource source, LocalRepoContext local) {
    if (config.baseSha() != null && !config.baseSha().isBlank()) {
      return config.baseSha();
    }
    RepoRef repo = new RepoRef(config.repoOwner(), config.repoName());
    List<String> chain = local.firstParentChain(config.headSha(), config.pushLookback() + 1);
    if (chain.isEmpty()) {
      throw new CraConfigException(
          "push 模式无法解析基线：本地仓库不可得（" + config.workspace() + "），请显式 --base/--baseline");
    }
    // 首父链 head 在前；从 head 的父提交开始找最近一次完整发布
    for (int i = 1; i < chain.size(); i++) {
      String sha = chain.get(i);
      CommentLedger ledger = source.fetchCommitBotComments(repo, sha);
      if (ledger.runMarkers().stream().anyMatch(LedgerRunMarker::isCompletion)) {
        return sha;
      }
    }
    // 首次评审：仓库初版 commit 作为基线（根 commit 自身变更不计入，05§2.4）
    return chain.get(chain.size() - 1);
  }
}
