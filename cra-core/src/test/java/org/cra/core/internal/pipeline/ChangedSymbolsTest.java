package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.semantic.FileReferenceStats;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolKind;
import org.cra.common.semantic.SymbolReferenceSite;
import org.cra.common.semantic.SymbolReferences;
import org.cra.common.semantic.TypeImport;
import org.junit.jupiter.api.Test;

/** 变更符号抽取（ADR-0014 决策 4）：定义优先、最内层定义归属、噪声排除与确定性排序。 */
class ChangedSymbolsTest {

  private static final String SHA = "a".repeat(40);
  private static final String KEEP_ALIVE = "src/main/java/demo/SessionKeepAlive.java";
  private static final String CALLER = "src/main/java/demo/Caller.java";
  private static final String PROBE = "src/main/java/demo/util/Probe.java";
  private static final String RARE = "src/main/java/demo/util/Rare.java";

  @Test
  void extractsDefinitionSymbolFromAddedLine() {
    DiffFile file =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -7,3 +7,5 @@\n public class SessionKeepAlive {\n"
                + "+  boolean shouldKeepAlive(Duration sessionAge) {\n"
                + "+    return true;\n"
                + " }",
            List.of(new DiffHunk(7, 3, 7, 5)));

    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);

    assertThat(symbols).extracting(ChangedSymbols.Symbol::name).contains("shouldKeepAlive");
    assertThat(symbols.stream().filter(s -> s.name().equals("shouldKeepAlive")).findFirst())
        .get()
        .satisfies(symbol -> assertThat(symbol.tier()).isZero());
  }

  @Test
  void enclosingDefinitionAttributesBodyOnlyChanges() {
    // 方法体改一行（签名不在新增行里）：靠"新增行 ≤ 最近定义行"归属到方法符号
    DiffFile file =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -11,2 +11,3 @@\n"
                + "   return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;\n"
                + "+  // 语义变化：边界收紧\n",
            List.of(new DiffHunk(11, 2, 11, 3)));

    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);

    assertThat(symbols).extracting(ChangedSymbols.Symbol::name).contains("shouldKeepAlive");
  }

  @Test
  void excludesKeywordsStdlibTypesAndUnknownIdentifiers() {
    DiffFile file =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -9,1 +9,2 @@\n" + "+  int localCount = 0; String label = \"SessionKeepAlive\";\n",
            List.of(new DiffHunk(9, 1, 9, 2)));

    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);

    assertThat(symbols)
        .extracting(ChangedSymbols.Symbol::name)
        .doesNotContain("int", "localCount", "String", "label", "SessionKeepAlive");
  }

  @Test
  void externalDefinitionNeedsTwoOccurrencesAndAvoidsStoplist() {
    DiffFile file =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -9,1 +9,3 @@\n+  Probe probe = new Probe();\n+  Rare rare = null;\n",
            List.of(new DiffHunk(9, 1, 9, 3)));

    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);
    List<String> names = symbols.stream().map(ChangedSymbols.Symbol::name).toList();

    // Probe：定义在 util/Probe.java，全仓出现 2 次 → Tier B 纳入
    assertThat(names).contains("Probe");
    // Rare：全仓仅 1 次（疑似局部/未使用）→ 排除
    assertThat(names).doesNotContain("Rare");
    assertThat(symbols.stream().filter(s -> s.name().equals("Probe")).findFirst())
        .get()
        .satisfies(symbol -> assertThat(symbol.tier()).isEqualTo(1));
  }

  @Test
  void deterministicOutputForSameInput() {
    DiffFile file =
        new DiffFile(
            KEEP_ALIVE,
            null,
            "modified",
            "@@ -9,1 +9,3 @@\n+  Probe probe = new Probe();\n+  boolean ok = shouldKeepAlive(null);\n",
            List.of(new DiffHunk(9, 1, 9, 3)));

    List<ChangedSymbols.Symbol> first =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);
    List<ChangedSymbols.Symbol> second =
        ChangedSymbols.extract(List.of(file), snapshot(), ChangedSymbols.MAX_SYMBOLS);

    assertThat(second).isEqualTo(first);
  }

  /** 手写快照：定义 / 引用 / import / 文件统计（与 jgit 实现产出的形状一致）。 */
  static SymbolIndexSnapshot snapshot() {
    List<SymbolDefinition> definitions =
        List.of(
            new SymbolDefinition(
                KEEP_ALIVE,
                "SessionKeepAlive",
                SymbolKind.TYPE,
                7,
                null,
                "public class SessionKeepAlive {"),
            new SymbolDefinition(
                KEEP_ALIVE,
                "shouldKeepAlive",
                SymbolKind.METHOD,
                9,
                "SessionKeepAlive",
                "boolean shouldKeepAlive(Duration sessionAge) {"),
            new SymbolDefinition(
                KEEP_ALIVE,
                "heartbeat",
                SymbolKind.METHOD,
                13,
                "SessionKeepAlive",
                "void heartbeat() {"),
            new SymbolDefinition(
                PROBE, "Probe", SymbolKind.TYPE, 5, null, "public final class Probe {"),
            new SymbolDefinition(
                PROBE, "probe", SymbolKind.METHOD, 7, "Probe", "static boolean probe() {"),
            new SymbolDefinition(
                RARE, "Rare", SymbolKind.TYPE, 5, null, "public final class Rare {"));
    List<SymbolReferences> references =
        List.of(
            new SymbolReferences(
                "shouldKeepAlive",
                4,
                List.of(
                    new SymbolReferenceSite(
                        KEEP_ALIVE, 9, "shouldKeepAlive(Duration sessionAge) {"),
                    new SymbolReferenceSite(CALLER, 9, "return keepAlive.shouldKeepAlive(age);"),
                    new SymbolReferenceSite(CALLER, 14, "keepAlive.shouldKeepAlive(age);"))),
            new SymbolReferences(
                "heartbeat",
                2,
                List.of(
                    new SymbolReferenceSite(KEEP_ALIVE, 13, "void heartbeat() {"),
                    new SymbolReferenceSite(CALLER, 18, "keepAlive.heartbeat();"))),
            new SymbolReferences(
                "Probe",
                2,
                List.of(
                    new SymbolReferenceSite(PROBE, 5, "public final class Probe {"),
                    new SymbolReferenceSite(KEEP_ALIVE, 9, "Probe probe = new Probe();"))),
            new SymbolReferences(
                "probe", 1, List.of(new SymbolReferenceSite(PROBE, 7, "static boolean probe() {"))),
            new SymbolReferences(
                "Rare", 1, List.of(new SymbolReferenceSite(RARE, 5, "public final class Rare {"))));
    List<TypeImport> imports =
        List.of(
            new TypeImport(
                CALLER, "demo.SessionKeepAlive", "SessionKeepAlive", false, 3, KEEP_ALIVE),
            new TypeImport(CALLER, "java.time.Duration", "Duration", false, 4, null),
            new TypeImport(
                "src/main/java/demo/Loader.java",
                "demo.SessionKeepAlive",
                "SessionKeepAlive",
                false,
                3,
                KEEP_ALIVE));
    List<FileReferenceStats> stats =
        List.of(
            new FileReferenceStats(KEEP_ALIVE, 3, 3, 2),
            new FileReferenceStats(CALLER, 0, 3, 0),
            new FileReferenceStats(PROBE, 2, 1, 0),
            new FileReferenceStats(RARE, 1, 0, 0));
    return new SymbolIndexSnapshot(
        true,
        SHA,
        "",
        4,
        0,
        false,
        List.of("语义索引第一版仅支持 Java 源文件（非 .java 文件跳过，不做多语言抽取）"),
        definitions,
        references,
        imports,
        stats);
  }
}
