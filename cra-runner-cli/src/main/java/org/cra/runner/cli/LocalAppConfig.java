package org.cra.runner.cli;

import java.nio.file.Path;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalGitAdapter;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 本地 pre-PR 模式组合根（02§5 本地形态；T2.10）：账本 = SQLite（--ledger-db，默认 ~/.cra/ledger.db）、 diff 数据面 = 本地
 * jgit（LocalGitAdapter，由命令层打开后注入）、无任何 GitHub 依赖（不建 GitHub bean）。 Spring 仅 DI/配置（R6/R7）。
 *
 * <p>与 CI 形态共用的 11 个 bean 在 {@link AbstractAppConfig}；本类只声明形态差异（本地 adapter 的 {@code @Primary} 与
 * SQLite 账本）。
 */
@Configuration
class LocalAppConfig extends AbstractAppConfig {

  private final LocalGitAdapter localGit;

  LocalAppConfig(CraConfig config, LocalGitAdapter localGit) {
    super(config);
    this.localGit = localGit;
  }

  @Bean
  @Primary
  GitPlatformAdapter gitPlatformAdapter() {
    // @Primary：LocalGitAdapter 同时被命令层 registerBean 注册（localGitAdapter）——本地形态
    // 的主 GitPlatformAdapter 恒为本 bean，避免 orchestrator 按类型注入时双候选歧义
    return localGit;
  }

  @Bean
  ReviewLedger reviewLedger() {
    // 本地形态事实源（02§5）：完成标记/finding 指纹/模板版本落 SQLite，幂等闸跨 run 生效
    return StoreAssembly.createSqliteLedger(
        config().repoOwner(), config().repoName(), Path.of(config().ledgerDb()));
  }
}
