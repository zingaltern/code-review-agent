package org.cra.runner.cli;

import org.cra.common.git.RepoRef;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组合根（10§3/10§4：M0–M3 全部对象图在 cra-runner-cli 装配；Spring 仅 DI/配置，@Autowired 零出现——R6/R7 守护）。各模块只经 api
 * 装配工厂触达（internal 跨模块不可见，R3）。
 *
 * <p>M4e 第二半（ADR-0013）：Git 平台按 {@link CraConfig#platform()} 装配——GitHub（默认）或 GitLab；两者都不依赖对方模块。
 * 与本地形态共用的 11 个 bean 在 {@link AbstractAppConfig}；本类只声明平台侧三件。
 */
@Configuration
class AppConfig extends AbstractAppConfig {

  AppConfig(CraConfig config) {
    super(config);
  }

  @Bean
  PlatformComponents platformComponents() {
    // ADR-0013：未选中的平台不构造、不读取其 token；GitLab 模式不需要 githubToken，反之亦然
    return PlatformComponents.create(config());
  }

  @Bean
  GitPlatformAdapter gitPlatformAdapter(PlatformComponents components) {
    return components.adapter();
  }

  @Bean
  ReviewLedger reviewLedger(PlatformComponents components) {
    // ADR-0005：评论账本构造器注入 CommentSource（同一实现的双端口视图）；
    // M4c/M4d：发布形态决定账本载体（PR 评论 / commit comment / issue，05§1.3）
    return StoreAssembly.createGitHubLedger(
        components.commentSource(),
        new RepoRef(config().repoOwner(), config().repoName()),
        config().reviewMode());
  }
}
