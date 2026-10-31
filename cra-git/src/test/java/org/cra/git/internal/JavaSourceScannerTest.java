package org.cra.git.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolKind;
import org.cra.common.semantic.TypeImport;
import org.junit.jupiter.api.Test;

/** Java 词法/结构抽取（ADR-0014 决策 2）：定义、import、标识符出现与容错。 */
class JavaSourceScannerTest {

  private static final String PATH = "src/main/java/demo/SessionKeepAlive.java";

  private static final String SOURCE =
      """
      package demo;

      import java.time.Duration;
      import static java.util.Objects.requireNonNull;

      /** 会话保活（注释里的 shouldKeepAlive 不算引用）。 */
      public class SessionKeepAlive {
        private static final int MINUTES = 30;

        boolean shouldKeepAlive(Duration sessionAge) {
          String marker = "shouldKeepAlive 在字符串里";
          char c = 'x';
          return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;
        }

        void heartbeat() {
          // no-op
        }

        static class Nested {
          int value() {
            return 1;
          }
        }
      }
      """;

  @Test
  void extractsTypeMethodAndFieldDefinitionsWithOwnersAndLines() {
    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan(PATH, SOURCE);

    assertThat(scan.packageName()).isEqualTo("demo");
    List<String> types =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.TYPE)
            .map(SymbolDefinition::name)
            .toList();
    assertThat(types).containsExactly("SessionKeepAlive", "Nested");

    SymbolDefinition shouldKeepAlive =
        scan.definitions().stream()
            .filter(d -> d.name().equals("shouldKeepAlive"))
            .findFirst()
            .orElseThrow();
    assertThat(shouldKeepAlive.kind()).isEqualTo(SymbolKind.METHOD);
    assertThat(shouldKeepAlive.owner()).isEqualTo("SessionKeepAlive");
    assertThat(shouldKeepAlive.line()).isEqualTo(10);
    assertThat(shouldKeepAlive.snippet())
        .startsWith("boolean shouldKeepAlive(Duration sessionAge)");

    SymbolDefinition minutes =
        scan.definitions().stream()
            .filter(d -> d.name().equals("MINUTES"))
            .findFirst()
            .orElseThrow();
    assertThat(minutes.kind()).isEqualTo(SymbolKind.FIELD);
    assertThat(minutes.owner()).isEqualTo("SessionKeepAlive");
    assertThat(minutes.line()).isEqualTo(8);

    SymbolDefinition nestedValue =
        scan.definitions().stream().filter(d -> d.name().equals("value")).findFirst().orElseThrow();
    assertThat(nestedValue.kind()).isEqualTo(SymbolKind.METHOD);
    assertThat(nestedValue.owner()).isEqualTo("Nested");
  }

  @Test
  void parsesSingleTypeAndStaticImports() {
    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan(PATH, SOURCE);

    assertThat(scan.imports())
        .extracting(TypeImport::imported)
        .containsExactly("java.time.Duration", "java.util.Objects.requireNonNull");
    assertThat(scan.imports())
        .extracting(TypeImport::simpleName)
        .containsExactly("Duration", "requireNonNull");
    assertThat(scan.imports()).extracting(TypeImport::staticImport).containsExactly(false, true);
    assertThat(scan.imports()).allMatch(entry -> entry.resolvedPath() == null);
  }

  @Test
  void identifierOccurrencesIgnoreCommentsStringsAndLiterals() {
    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan(PATH, SOURCE);

    // 注释与字符串里的 shouldKeepAlive 不计；代码里的 1 次定义 + 1 次字符串外出现
    assertThat(scan.identifierLines().get("shouldKeepAlive")).hasSize(1);
    assertThat(scan.identifierLines()).doesNotContainKeys("public", "class", "return", "true");
    // 字符串内容 "shouldKeepAlive 在字符串里" 不应贡献任何标识符
    assertThat(scan.identifierLines().keySet()).doesNotContain("在字符串里");
    assertThat(scan.identifierLines().get("Duration")).hasSize(2);
  }

  @Test
  void deterministicForSameInput() {
    JavaSourceScanner.FileScan first = JavaSourceScanner.scan(PATH, SOURCE);
    JavaSourceScanner.FileScan second = JavaSourceScanner.scan(PATH, SOURCE);

    assertThat(second.definitions()).isEqualTo(first.definitions());
    assertThat(second.imports()).isEqualTo(first.imports());
    assertThat(second.identifierLines()).isEqualTo(first.identifierLines());
  }

  @Test
  void interfaceMethodsAndEnumConstantsAreRecognised() {
    String source =
        """
        package demo;

        public interface Handler {
          void handle(String payload);
          default int priority() { return 0; }
        }

        enum Mode { FAST, SLOW;
          int rank() { return ordinal(); }
        }
        """;

    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan("Handler.java", source);
    List<String> methods =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.METHOD)
            .map(SymbolDefinition::name)
            .collect(Collectors.toList());
    List<String> fields =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.FIELD)
            .map(SymbolDefinition::name)
            .collect(Collectors.toList());

    assertThat(methods).containsExactly("handle", "priority", "rank");
    assertThat(fields).containsExactly("FAST", "SLOW");
  }

  @Test
  void enumConstantsWithArgumentsAreExtracted() {
    String source =
        """
        package demo;

        enum Level {
          LOW(1), HIGH(2);

          private final int rank;

          Level(int rank) { this.rank = rank; }
        }
        """;

    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan("Level.java", source);
    List<String> fields =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.FIELD)
            .map(SymbolDefinition::name)
            .toList();

    assertThat(fields).contains("LOW", "HIGH", "rank");
  }

  @Test
  void enumConstantsWithClassBodyAreExtracted() {
    String source =
        """
        package demo;

        enum Flag {
          ON {
            void apply() {}
          },
          OFF;
        }
        """;

    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan("Flag.java", source);
    List<String> fields =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.FIELD)
            .map(SymbolDefinition::name)
            .toList();

    assertThat(fields).contains("ON", "OFF");
  }

  @Test
  void contextualKeywordsRemainUsableAsMethodNames() {
    // module-info 的上下文关键字（open/to/with/uses…）不是普通源码的保留字
    String source =
        """
        package demo;

        class Ctx {
          void open() {}
          void to(String value) {}
          String with(String value) { return value; }
          void uses() {}
        }
        """;

    JavaSourceScanner.FileScan scan = JavaSourceScanner.scan("Ctx.java", source);
    List<String> methods =
        scan.definitions().stream()
            .filter(d -> d.kind() == SymbolKind.METHOD)
            .map(SymbolDefinition::name)
            .toList();

    assertThat(methods).containsExactly("open", "to", "with", "uses");
  }

  @Test
  void toleratesUnbalancedBracesAndEmptySource() {
    assertThat(JavaSourceScanner.scan("Broken.java", "class Broken { void x() {").definitions())
        .isNotEmpty();
    assertThat(JavaSourceScanner.scan("Empty.java", "").definitions()).isEmpty();
    assertThat(JavaSourceScanner.scan("Null.java", null).definitions()).isEmpty();
  }
}
