package org.cra.git.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.semantic.FileReferenceStats;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexOptions;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolKind;
import org.cra.common.semantic.SymbolReferenceSite;
import org.cra.common.semantic.TypeImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 语义索引默认实现（ADR-0014）：真实 git fixture 上的定义/引用/import 抽取、上限与截断声明、不可得语义与确定性。
 *
 * <p>LLM 与网络均不参与（08§3.1）：索引是纯本地数据面。
 */
class JgitSymbolIndexTest {

  private static final String KEEP_ALIVE = "src/main/java/demo/SessionKeepAlive.java";
  private static final String CALLER = "src/main/java/demo/Caller.java";
  private static final String ENTRY = "src/main/java/demo/app/Entry.java";
  private static final String BIG = "src/main/java/demo/Big.java";

  @TempDir Path workspace;

  private String headSha;

  private static final String KEEP_ALIVE_SRC =
      """
      package demo;

      import java.time.Duration;

      public class SessionKeepAlive {
        private static final int MINUTES = 30;

        boolean shouldKeepAlive(Duration sessionAge) {
          return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;
        }

        void heartbeat() {
          // no-op
        }
      }
      """;

  private static final String CALLER_SRC =
      """
      package demo;

      import java.time.Duration;

      public class Caller {
        private final SessionKeepAlive keepAlive = new SessionKeepAlive();

        boolean check(Duration age) {
          return keepAlive.shouldKeepAlive(age);
        }
      }
      """;

  private static final String ENTRY_SRC =
      """
      package demo.app;

      import demo.SessionKeepAlive;

      public class Entry {
        void boot() {
          new SessionKeepAlive().heartbeat();
        }
      }
      """;

  @BeforeEach
  void initRepo() throws Exception {
    git("init", "-q");
    Files.writeString(workspace.resolve("README.md"), "demo\n");
    Files.createDirectories(workspace.resolve(KEEP_ALIVE).getParent());
    Files.writeString(workspace.resolve(KEEP_ALIVE), KEEP_ALIVE_SRC);
    Files.writeString(workspace.resolve(CALLER), CALLER_SRC);
    Files.createDirectories(workspace.resolve(ENTRY).getParent());
    Files.writeString(workspace.resolve(ENTRY), ENTRY_SRC);
    // 跳过目录：构建产物不参与索引
    Files.createDirectories(workspace.resolve("target/generated"));
    Files.writeString(
        workspace.resolve("target/generated/Generated.java"),
        "package generated; class Generated {}\n");
    git("add", ".");
    git("-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", "init");
    headSha = git("rev-parse", "HEAD").strip();
  }

  @Test
  void extractsDefinitionsReferencesAndImportResolution() {
    SymbolIndexSnapshot snapshot = new JgitSymbolIndex(workspace).build(headSha);

    assertThat(snapshot.available()).isTrue();
    assertThat(snapshot.headSha()).isEqualTo(headSha);
    assertThat(snapshot.filesScanned()).isEqualTo(3);
    assertThat(snapshot.notes()).anyMatch(note -> note.contains("仅支持 Java"));

    SymbolDefinition method =
        snapshot.definitionsOf("shouldKeepAlive").stream()
            .filter(definition -> definition.kind() == SymbolKind.METHOD)
            .findFirst()
            .orElseThrow();
    assertThat(method.path()).isEqualTo(KEEP_ALIVE);
    assertThat(method.owner()).isEqualTo("SessionKeepAlive");
    assertThat(method.line()).isEqualTo(8);
    assertThat(snapshot.definitionsOfPath(KEEP_ALIVE))
        .extracting(SymbolDefinition::name)
        .contains("shouldKeepAlive", "MINUTES", "heartbeat");

    // 跨文件引用：Caller 第 9 行 / Entry 第 7 行（heartbeat 调用在 Entry）
    List<SymbolReferenceSite> callSites = snapshot.referencesOf("shouldKeepAlive");
    assertThat(callSites).extracting(SymbolReferenceSite::path).contains(CALLER);
    assertThat(callSites).extracting(SymbolReferenceSite::line).contains(9);
    assertThat(callSites.stream().map(SymbolReferenceSite::snippet).toList())
        .anyMatch(snippet -> snippet.contains("shouldKeepAlive(age)"));
    assertThat(snapshot.referencesOf("heartbeat"))
        .extracting(SymbolReferenceSite::path)
        .contains(ENTRY)
        .doesNotContain(CALLER);

    // import 解析：仓库内类型可定位，JDK 类型保持 null
    List<TypeImport> entryImports = snapshot.importsOf(ENTRY);
    assertThat(entryImports)
        .filteredOn(entry -> entry.imported().equals("demo.SessionKeepAlive"))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.resolvedInRepo()).isTrue();
              assertThat(entry.resolvedPath()).isEqualTo(KEEP_ALIVE);
              assertThat(entry.line()).isEqualTo(3);
            });
    assertThat(snapshot.importsOf(CALLER))
        .filteredOn(entry -> entry.imported().equals("java.time.Duration"))
        .singleElement()
        .satisfies(entry -> assertThat(entry.resolvedPath()).isNull());

    // 文件级统计：定义文件的入边引用 > 0
    FileReferenceStats keepAliveStats =
        snapshot.fileStats().stream()
            .filter(stats -> stats.path().equals(KEEP_ALIVE))
            .findFirst()
            .orElseThrow();
    assertThat(keepAliveStats.definitions()).isPositive();
    assertThat(keepAliveStats.inboundReferences()).isPositive();
  }

  @Test
  void deterministicAcrossBuilds() {
    JgitSymbolIndex index = new JgitSymbolIndex(workspace);

    SymbolIndexSnapshot first = index.build(headSha);
    SymbolIndexSnapshot second = index.build(headSha);

    assertThat(second).isEqualTo(first);
    assertThat(second.definitions()).isEqualTo(first.definitions());
    assertThat(second.references()).isEqualTo(first.references());
    assertThat(second.imports()).isEqualTo(first.imports());
  }

  @Test
  void maxFilesTruncatesDeterministicallyAndDeclares() {
    JgitSymbolIndex index = new JgitSymbolIndex(workspace);
    SymbolIndexOptions options =
        new SymbolIndexOptions(
            1,
            262_144L,
            java.util.Set.of(),
            java.util.Set.of(),
            SymbolIndexOptions.OverLimitPolicy.TRUNCATE);

    SymbolIndexSnapshot snapshot = index.build(headSha, options);

    assertThat(snapshot.available()).isTrue();
    assertThat(snapshot.filesScanned()).isEqualTo(1);
    assertThat(snapshot.truncated()).isTrue();
    assertThat(snapshot.notes()).anyMatch(note -> note.contains("扫描上限"));
    // 升序取前 1 个：src/main/java/demo/Caller.java
    assertThat(snapshot.definitions()).allMatch(definition -> definition.path().equals(CALLER));
  }

  @Test
  void overLimitUnavailablePolicyReturnsNoPartialIndex() {
    JgitSymbolIndex index = new JgitSymbolIndex(workspace);
    SymbolIndexOptions options =
        new SymbolIndexOptions(
            1,
            262_144L,
            java.util.Set.of(),
            java.util.Set.of(),
            SymbolIndexOptions.OverLimitPolicy.UNAVAILABLE);

    SymbolIndexSnapshot snapshot = index.build(headSha, options);

    assertThat(snapshot.available()).isFalse();
    assertThat(snapshot.reason()).contains("超过上限");
    assertThat(snapshot.definitions()).isEmpty();
  }

  @Test
  void oversizedAndBinaryFilesAreSkippedWithDeclaration() throws Exception {
    Files.writeString(
        workspace.resolve(BIG), "package demo;\nclass Big {\n" + "  // pad\n".repeat(600) + "}\n");
    git("add", ".");
    git("-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", "big");
    String sha = git("rev-parse", "HEAD").strip();

    JgitSymbolIndex index = new JgitSymbolIndex(workspace);
    SymbolIndexOptions options =
        new SymbolIndexOptions(
            10,
            2_048L,
            java.util.Set.of(),
            java.util.Set.of(),
            SymbolIndexOptions.OverLimitPolicy.TRUNCATE);
    SymbolIndexSnapshot snapshot = index.build(sha, options);

    assertThat(snapshot.available()).isTrue();
    assertThat(snapshot.definitionsOfPath(BIG)).isEmpty();
    assertThat(snapshot.filesSkipped()).isEqualTo(1);
    assertThat(snapshot.notes()).anyMatch(note -> note.contains("跳过超长文件 " + BIG));
  }

  @Test
  void includeAndExcludePatternsFilterPaths() {
    JgitSymbolIndex index = new JgitSymbolIndex(workspace);
    SymbolIndexOptions include =
        new SymbolIndexOptions(
            10,
            262_144L,
            java.util.Set.of(KEEP_ALIVE),
            java.util.Set.of(),
            SymbolIndexOptions.OverLimitPolicy.TRUNCATE);
    SymbolIndexSnapshot includeOnly = index.build(headSha, include);
    assertThat(includeOnly.filesScanned()).isEqualTo(1);
    assertThat(includeOnly.definitionsOfPath(KEEP_ALIVE)).isNotEmpty();
    assertThat(includeOnly.definitionsOfPath(CALLER)).isEmpty();
    assertThat(includeOnly.definitionsOfPath(ENTRY)).isEmpty();

    SymbolIndexOptions exclude =
        new SymbolIndexOptions(
            10,
            262_144L,
            java.util.Set.of(),
            java.util.Set.of("src/main/java/demo/app/*"),
            SymbolIndexOptions.OverLimitPolicy.TRUNCATE);
    SymbolIndexSnapshot excludeOnly = index.build(headSha, exclude);
    assertThat(excludeOnly.filesScanned()).isEqualTo(2);
    assertThat(excludeOnly.definitionsOfPath(ENTRY)).isEmpty();
    assertThat(excludeOnly.definitionsOfPath(CALLER)).isNotEmpty();
  }

  @Test
  void unavailableForNonRepoBadShaAndNullRoot() {
    SymbolIndexSnapshot nonRepo = new JgitSymbolIndex(workspace.resolve("missing")).build(headSha);
    assertThat(nonRepo.available()).isFalse();
    assertThat(nonRepo.reason()).contains("不是 git checkout");

    SymbolIndexSnapshot badSha = new JgitSymbolIndex(workspace).build("deadbeefdeadbeef");
    assertThat(badSha.available()).isFalse();
    assertThat(badSha.reason()).contains("head 不可解析");

    SymbolIndexSnapshot nullRoot = new JgitSymbolIndex(null).build(headSha);
    assertThat(nullRoot.available()).isFalse();
  }

  private String git(String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(List.of(args));
    Process process = new ProcessBuilder(cmd).directory(workspace.toFile()).start();
    process.waitFor();
    return new String(process.getInputStream().readAllBytes());
  }
}
