package org.cra.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 10§5 架构守护规则 R1–R8。
 *
 * <p>单一事实源是 10§2 依赖矩阵：R1 层规则做粗粒度复刻，矩阵中未被层规则覆盖的 ❌ 单元由 {@link #matrixForbiddenPairs()}
 * 表驱动逐对生成补齐。类视图只导入 org.cra 主代码 （排除测试类——cra-archtest 自身类不入图，避免自引用污染）。
 */
class ArchitectureRulesTest {

  /** 模块 → 顶层包。git-github 的 org.cra.git.github.. 命中 org.cra.git.. 前缀（10§5 R1 注）。 */
  private static final Map<String, String> MODULE_PACKAGE =
      Map.ofEntries(
          Map.entry("common", "org.cra.common"),
          Map.entry("llm", "org.cra.llm"),
          Map.entry("git", "org.cra.git"),
          Map.entry("git-gh", "org.cra.git.github"),
          Map.entry("context", "org.cra.context"),
          Map.entry("agents", "org.cra.agents"),
          Map.entry("store", "org.cra.store"),
          Map.entry("core", "org.cra.core"),
          Map.entry("cli", "org.cra.runner.cli"),
          Map.entry("eval", "org.cra.eval"));

  /** 10§2 矩阵：行 → 允许依赖的列（common 与自身恒允许，不列；M4 的 cra-server 未建，无行）。 */
  private static final Map<String, Set<String>> MATRIX_ALLOWED =
      Map.ofEntries(
          Map.entry("llm", Set.of()),
          Map.entry("git", Set.of()),
          Map.entry("git-gh", Set.of("git")),
          Map.entry("context", Set.of()),
          Map.entry("agents", Set.of()),
          Map.entry("store", Set.of()),
          Map.entry("core", Set.of("llm", "git", "context", "agents", "store")),
          Map.entry("cli", Set.of("llm", "git", "git-gh", "context", "agents", "store", "core")),
          Map.entry("eval", Set.of("git", "git-gh", "store")));

  /** 含 internal 包、适用 R3 的模块（10§3：common 与 cli 免二分；archtest 无主代码）。 */
  private static final List<String> MODULES_WITH_INTERNAL =
      List.of("llm", "git", "git-gh", "context", "agents", "store", "core", "eval");

  @Test
  void r1LayeredDependencies() {
    Architectures.LayeredArchitecture layered =
        Architectures.layeredArchitecture()
            .consideringAllDependencies()
            .layer("Common")
            .definedBy("org.cra.common..")
            .layer("Infra")
            .definedBy("org.cra.llm..", "org.cra.git..", "org.cra.context..")
            .layer("Store")
            .definedBy("org.cra.store..")
            .layer("Agents")
            .definedBy("org.cra.agents..")
            .layer("Core")
            .definedBy("org.cra.core..")
            .layer("Shell")
            .definedBy("org.cra.runner.cli..")
            .layer("Eval")
            .definedBy("org.cra.eval..")
            .whereLayer("Shell")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Eval")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Core")
            .mayOnlyBeAccessedByLayers("Shell")
            .whereLayer("Agents")
            .mayOnlyBeAccessedByLayers("Core", "Shell")
            .whereLayer("Store")
            .mayOnlyBeAccessedByLayers("Core", "Shell", "Eval")
            // Infra 对 Eval 放开 = ADR-0005（eval→git(+git-gh) 仅 api 取数）；eval→llm/context
            // 仍由矩阵逐对规则封死（10§2 单一事实源，10§5 R1 片段未含 Eval 的差异按矩阵修）
            .whereLayer("Infra")
            .mayOnlyBeAccessedByLayers("Core", "Shell", "Eval");
    layered.check(importedClasses());
  }

  @Test
  void r2NoModuleCycles() {
    SlicesRuleDefinition.slices()
        .matching("org.cra.(*)..")
        .should()
        .beFreeOfCycles()
        .check(importedClasses());
  }

  @Test
  void r3InternalPackagesNotVisibleAcrossModules() {
    for (String module : MODULES_WITH_INTERNAL) {
      String pkg = MODULE_PACKAGE.get(module);
      ArchRule rule =
          noClasses()
              .that()
              .resideOutsideOfPackage(pkg + "..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage(pkg + ".internal..")
              .because("10§5 R3：模块间只允许依赖 api 包（10§4）");
      rule.check(importedClasses());
    }
  }

  @Test
  void r4SpiModuleKnowsNoImplementation() {
    // org.cra.git..（SPI）不得依赖 org.cra.git.github..（实现）；行选择器须排除 github 自身，
    // 否则 github 内部依赖被误判。org.cra.common.. 不依赖任何其他模块（矩阵底座纯度）。
    ArchRule spiRule =
        noClasses()
            .that()
            .resideInAPackage("org.cra.git..")
            .and()
            .resideOutsideOfPackage("org.cra.git.github..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.cra.git.github..")
            .because("10§5 R4：SPI 模块不知实现");
    spiRule.check(importedClasses());
    for (String module : MODULE_PACKAGE.keySet()) {
      if (module.equals("common")) {
        continue;
      }
      noClasses()
          .that()
          .resideInAPackage("org.cra.common..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(MODULE_PACKAGE.get(module) + "..")
          .because("10§5 R4：cra-common 底座纯度（不依赖任何模块）")
          .check(importedClasses());
    }
  }

  @Test
  void r5AgentsIndependentOfCore() {
    noClasses()
        .that()
        .resideInAPackage("org.cra.agents..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.cra.core..")
        .because("10§5 R5：子代理定义独立于编排器（03§1）")
        .check(importedClasses());
  }

  @Test
  void r6SpringOnlyInCompositionRoot() {
    noClasses()
        .that()
        .resideOutsideOfPackage("org.cra.runner.cli..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .because("10§5 R6：Spring 仅 DI/配置且仅在组合根（01§5.1、02§7）")
        .check(importedClasses());
  }

  @Test
  void r7NoInjectionAnnotations() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(Autowired.class)
        .because("10§5 R7：构造器注入唯一，@Autowired 全项目零出现（10§4）")
        .check(importedClasses());
  }

  @Test
  void r8NoReflection() {
    noClasses()
        .that()
        .resideInAPackage("org.cra..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.lang.reflect..")
        .because("10§5 R8：跨层反射是绕过 api/internal 的万能钥匙")
        .check(importedClasses());
  }

  @Test
  void matrixForbiddenPairs() {
    // R1 层规则未覆盖的 ❌ 单元逐对补齐（10§2：矩阵是单一事实源，规则循环生成）。
    // git 行选择器须排除 github 子包；git 列目标包为前缀匹配（github 同被矩阵 ❌ 覆盖，超集无害）。
    for (var row : MATRIX_ALLOWED.entrySet()) {
      for (var col : MODULE_PACKAGE.keySet()) {
        if (col.equals("common") || col.equals(row.getKey()) || row.getValue().contains(col)) {
          continue;
        }
        String target = MODULE_PACKAGE.get(col) + "..";
        if (row.getKey().equals("git")) {
          noClasses()
              .that()
              .resideInAPackage("org.cra.git..")
              .and()
              .resideOutsideOfPackage("org.cra.git.github..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage(target)
              .because("10§2 矩阵 ❌ 单元：cra-git → " + col)
              .check(importedClasses());
        } else {
          noClasses()
              .that()
              .resideInAPackage(MODULE_PACKAGE.get(row.getKey()) + "..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage(target)
              .because("10§2 矩阵 ❌ 单元：cra-" + row.getKey() + " → " + col)
              .check(importedClasses());
        }
      }
    }
  }

  @Test
  void r0ClassViewNonVacuous() {
    // 防"0 类空转绿灯"（10§5 同源问题）：reactor 内模块类以 target/classes 目录入 classpath，
    // 单模块构建时以本地仓库 jar 入 classpath——两种形态都必须导入到 org.cra 类。
    com.tngtech.archunit.core.domain.JavaClasses classes = importedClasses();
    if (classes.size() < 10) {
      throw new AssertionError(
          "org.cra 类视图只有 " + classes.size() + " 个类，架构规则将在空集上空转；" + "请从仓库根执行 mvn verify（00§4 自检口径）");
    }
  }

  private static com.tngtech.archunit.core.domain.JavaClasses importedClasses() {
    return new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("org.cra");
  }
}
