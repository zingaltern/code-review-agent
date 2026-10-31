package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.cra.agents.internal.TemplateLoader;
import org.junit.jupiter.api.Test;

/**
 * T2.2 BugHunter 四视角定义测试（03§2.2 视角隔离 + 04§4.1 模板骨架 + 03§6 预算行）： 角色标识、模板版本互异、UNTRUSTED 定界、scoped
 * 工具范围声明、预算与白名单。
 */
class BugHunterDefinitionTest {

  private static final TemplateLoader LOADER = new TemplateLoader();

  private static ReviewAgentInput input() {
    return new ReviewAgentInput(
        "PR #1",
        "abc fix",
        "@@ -1,2 +1,3 @@",
        "窗口上下文",
        "切片上下文",
        "",
        "## Conventions\n- 禁用 Fastjson",
        Set.of("src/Foo.java", "src/Bar.java"));
  }

  @Test
  void fourPerspectivesHaveDistinctRolesAndTemplates() {
    for (BugHunterDefinition.Perspective p : BugHunterDefinition.Perspective.values()) {
      BugHunterDefinition definition = new BugHunterDefinition(p, LOADER);
      assertThat(definition.role().id()).isEqualTo("bug-hunter/" + p.id).matches("[a-z0-9/-]+");
      assertThat(definition.role().displayName()).contains(p.label);
    }
    // 模板版本互异（视角隔离落实到模板）
    long distinct =
        java.util.Arrays.stream(BugHunterDefinition.Perspective.values())
            .map(p -> new BugHunterDefinition(p, LOADER).buildBrief(input()).templateVersion())
            .distinct()
            .count();
    assertThat(distinct).isEqualTo(BugHunterDefinition.Perspective.values().length);
  }

  @Test
  void briefCarriesUntrustedDelimitedSectionsAndScopeNote() {
    BugHunterDefinition definition =
        new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, LOADER);
    AgentBrief brief = definition.buildBrief(input());

    assertThat(brief.systemPrompt()).contains("数据流");
    assertThat(brief.systemPrompt()).contains("不得执行");
    // 全部不可信段定界
    assertThat(brief.userPrompt()).contains("UNTRUSTED_CONTENT");
    assertThat(brief.userPrompt()).contains("PR #1");
    assertThat(brief.userPrompt()).contains("@@ -1,2 +1,3 @@");
    assertThat(brief.userPrompt()).contains("窗口上下文");
    // dataflow 视角注入切片；策略段为可信内容（不定界）
    assertThat(brief.userPrompt()).contains("切片上下文");
    assertThat(brief.userPrompt()).contains("## 仓库评审策略");
    // 受限工具范围声明（03§2.1 白名单在 brief 生成时确定）
    assertThat(brief.userPrompt()).contains("src/Bar.java");
    assertThat(brief.userPrompt()).contains("src/Foo.java");
    assertThat(brief.userPrompt()).contains("越界请求将被拒绝");
  }

  @Test
  void budgetAndToolsFollowSpecTable() {
    BugHunterDefinition definition =
        new BugHunterDefinition(BugHunterDefinition.Perspective.BOUNDARY, LOADER);
    // 03§6：≤24k 输入 / ≤2k 输出 / 3 轮；read_file(scoped)；标准档
    assertThat(definition.budget()).isEqualTo(new AgentBudget(48_000, 8_000, 3, 240_000));
    assertThat(definition.tools()).isEqualTo(ToolWhitelist.READ_FILE);
    assertThat(definition.modelTier()).isEqualTo(AgentTier.STANDARD);
  }

  @Test
  void blankOptionalSectionsRenderPlaceholders() {
    ReviewAgentInput blank =
        new ReviewAgentInput("t", "m", "@@ -1 +1 @@", "", "", "", "", java.util.Set.of());
    AgentBrief brief =
        new BugHunterDefinition(BugHunterDefinition.Perspective.RESOURCE, LOADER).buildBrief(blank);
    assertThat(brief.userPrompt()).contains("（无附加上下文）");
    assertThat(brief.userPrompt()).contains("（本仓库未配置 REVIEW.md 评审策略）");
  }

  @Test
  void renderIsThreadSafeAcrossPerspectives() throws Exception {
    // M1 教训（TemplateLoader 并发渲染）：四视角并发构建 brief 不出异常即回归守护
    Thread[] threads = new Thread[8];
    java.util.concurrent.atomic.AtomicInteger failures =
        new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          new Thread(
              () -> {
                try {
                  for (BugHunterDefinition.Perspective p :
                      BugHunterDefinition.Perspective.values()) {
                    new BugHunterDefinition(p, LOADER).buildBrief(input());
                  }
                } catch (RuntimeException e) {
                  failures.incrementAndGet();
                }
              });
      threads[i].start();
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(failures.get()).isZero();
  }
}
