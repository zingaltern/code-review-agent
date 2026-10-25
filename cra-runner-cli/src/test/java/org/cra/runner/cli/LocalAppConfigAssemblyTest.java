package org.cra.runner.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.LocalGitAdapter;
import org.cra.git.internal.LocalJgitAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 本地 pre-PR 组合根装配冒烟（08§2.3）：命令层 {@code registerBean(LocalGitAdapter.class)}（bean 名
 * localGitAdapter）与 LocalAppConfig 的 gitPlatformAdapter bean 并存时，orchestrator 按类型注入必须无歧义。
 *
 * <p>本测试守护 M2 验收 8 实跑发现的 NoUniqueBeanDefinitionException（修复 = gitPlatformAdapter 标
 * {@code @Primary}）；无密钥路径因命令层跳过装配而不覆盖此缺陷，故需独立装配断言。
 */
class LocalAppConfigAssemblyTest {

  @TempDir Path repo;

  @Test
  void orchestratorResolvesWithLocalGitAdapterRegistered() throws Exception {
    try (var git = org.eclipse.jgit.api.Git.init().setDirectory(repo.toFile()).call()) {
      Files.writeString(repo.resolve("A.java"), "public class A {}");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("init")
          .setAuthor("t", "t@t")
          .setCommitter("t", "t@t")
          .setSign(false)
          .call();
    }
    try (LocalGitAdapter adapter = new LocalJgitAdapter(repo)) {
      CraConfig config = config(repo);
      try (var ctx = new AnnotationConfigApplicationContext()) {
        ctx.registerBean(CraConfig.class, () -> config);
        ctx.registerBean(LocalGitAdapter.class, () -> adapter);
        ctx.register(LocalAppConfig.class);
        ctx.refresh();
        assertThat(ctx.getBean(Orchestrator.class)).isNotNull();
      }
    }
  }

  private static CraConfig config(Path repo) {
    return new CraConfig(
        "local",
        "fixture",
        0,
        "HEAD",
        "HEAD",
        "quick",
        true,
        false,
        "",
        "https://api.github.com",
        "sk-test",
        "http://127.0.0.1:9",
        "test-model",
        repo.resolve(".cra/runs").toString(),
        repo.toString(),
        2000,
        8000,
        5,
        500_000L,
        0.70,
        0.85,
        "never",
        20,
        true,
        "true",
        repo.resolve("ledger.db").toString(),
        null,
        null,
        "standard,light",
        "");
  }
}
