package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.TypeImport;
import org.junit.jupiter.api.Test;

/** 确定性调用点索引（ADR-0015 §2.2）：符号抽取、同目录有界扫描、不可得降级、渲染预算。 */
class CallSiteIndexTest {

  private static final String FILE = "src/main/java/com/demo/util/TextBuffer.java";

  private static final String TEXT_BUFFER =
      """
      package com.demo.util;
      public final class TextBuffer {
        public char[] getTextBuffer() {
          if (!_hasSegments)  return _currentSegment;
          return contentsAsArray();
        }
        public char[] contentsAsArray() {
          return _resultArray;
        }
      }
      """;

  private static final String PEER =
      """
      package com.demo.util;
      public class JsonParser {
        char[] text() {
          return buffer.getTextBuffer();
        }
      }
      """;

  private static final String PATCH =
      """
      @@ -304,7 +304,7 @@
      -        if (!_hasSegments && _currentSegment != null)  return _currentSegment;
      +        if (!_hasSegments)  return _currentSegment;
      """;

  private static FindingAggregator.Scored scored(
      String id, FindingCategory category, String evidence) {
    CandidateFinding finding =
        new CandidateFinding(
            id,
            "run_1",
            category,
            "getTextBuffer 移除空检查后可能返回 null",
            "空缓冲区路径返回 null",
            "触发条件：空缓冲区；错误结果：调用方 NPE",
            FILE,
            307,
            Side.RIGHT,
            evidence,
            "",
            Severity.IMPORTANT,
            false,
            0.9,
            "bug-hunter/dataflow",
            "glm-x");
    return new FindingAggregator.Scored(
        finding, "fp", org.cra.common.finding.AnchorStatus.INLINE, null);
  }

  private static DiffResult diff() {
    return new DiffResult(
        "b",
        "h",
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(304, 7, 304, 7)))),
        List.of());
  }

  @Test
  void indexesPeerFilesForSymbolsInFindingAndPatch() {
    Map<String, String> files =
        Map.of(FILE, TEXT_BUFFER, "src/main/java/com/demo/util/JsonParser.java", PEER);
    CallSiteIndex index =
        CallSiteIndex.build(
            FILE,
            List.of(
                scored(
                    "f_1",
                    FindingCategory.BUG,
                    "L307: if (!_hasSegments)  return _currentSegment; — 空缓冲区时 getTextBuffer 返回 null")),
            diff(),
            List.copyOf(files.keySet()),
            path -> Optional.ofNullable(files.get(path)));

    assertThat(index.available()).isTrue();
    assertThat(index.symbols()).contains("getTextBuffer");
    assertThat(index.entries())
        .anySatisfy(
            e -> {
              assertThat(e.path()).isEqualTo("src/main/java/com/demo/util/JsonParser.java");
              assertThat(e.line()).isEqualTo(4);
            });
    String rendered = index.render();
    assertThat(rendered).contains("调用点索引").contains("JsonParser.java:4").contains("调用链不完整");
    assertThat(TokenMath.estimateTokens(rendered))
        .isLessThanOrEqualTo(CallSiteIndex.RENDER_TOKEN_CAP + 20);
  }

  // ---------- 跨层（import/import static 关联 caller；ADR-0015 §2.2 修订） ----------

  private static final String SERVICE = "src/main/java/com/demo/service/UserService.java";

  private static final String SERVICE_TEXT =
      """
      package com.demo.service;

      public class UserService {
        static final int DEFAULT_LIMIT = 10;

        boolean shouldKeepAlive(java.time.Duration sessionAge) {
          return sessionAge.compareTo(java.time.Duration.ofMinutes(30)) < 0;
        }
      }
      """;

  private static final String CONTROLLER = "src/main/java/com/demo/web/UserController.java";

  private static final String CONTROLLER_TEXT =
      """
      package com.demo.web;

      import com.demo.service.UserService;

      public class UserController {
        boolean handle(UserService userService, java.time.Duration age) {
          return userService.shouldKeepAlive(age);
        }
      }
      """;

  private static final String REPOSITORY = "src/main/java/com/demo/repository/UserRepository.java";

  private static final String REPOSITORY_TEXT =
      """
      package com.demo.repository;

      import static com.demo.service.UserService.shouldKeepAlive;

      public class UserRepository {
        boolean keep(java.time.Duration age) {
          return shouldKeepAlive(age);
        }
      }
      """;

  private static final String UNRELATED = "src/main/java/com/demo/other/Billing.java";

  private static final String UNRELATED_TEXT =
      """
      package com.demo.other;

      public class Billing {
        // 与本 PR 变更类型无关：只是同名方法（不 import 变更类型）
        String describe() {
          return "shouldKeepAlive";
        }
      }
      """;

  private static CallSiteIndex buildCrossPackage(
      Map<String, String> files, List<TypeImport> imports) {
    SymbolIndexSnapshot snapshot =
        new SymbolIndexSnapshot(
            true,
            "h",
            "",
            files.size(),
            0,
            false,
            List.of(),
            List.of(),
            List.of(),
            imports,
            List.of());
    return CallSiteIndex.build(
        SERVICE,
        List.of(scored("f_1", FindingCategory.BUG, "L6: shouldKeepAlive 对 sessionAge 未判空，调用方 NPE")),
        null,
        List.copyOf(files.keySet()),
        path -> Optional.ofNullable(files.get(path)),
        snapshot);
  }

  @Test
  void crossPackageCallersFoundViaImportsAndStaticImports() {
    Map<String, String> files =
        Map.of(
            SERVICE, SERVICE_TEXT,
            CONTROLLER, CONTROLLER_TEXT,
            REPOSITORY, REPOSITORY_TEXT,
            UNRELATED, UNRELATED_TEXT);
    List<TypeImport> imports =
        List.of(
            new TypeImport(
                CONTROLLER, "com.demo.service.UserService", "UserService", false, 3, SERVICE),
            new TypeImport(
                REPOSITORY,
                "com.demo.service.UserService.shouldKeepAlive",
                "shouldKeepAlive",
                true,
                3,
                null));

    CallSiteIndex index = buildCrossPackage(files, imports);

    assertThat(index.available()).isTrue();
    assertThat(index.importsAvailable()).isTrue();
    assertThat(index.callerCandidates()).isEqualTo(2);
    assertThat(index.symbols()).contains("shouldKeepAlive");
    // self = 变更文件自身；caller = web 层与 repository 层（跨包、跨 import 形态）
    assertThat(index.entries())
        .anySatisfy(
            e -> {
              assertThat(e.direction()).isEqualTo(CallSiteIndex.DIRECTION_SELF);
              assertThat(e.path()).isEqualTo(SERVICE);
            })
        .anySatisfy(
            e -> {
              assertThat(e.direction()).isEqualTo(CallSiteIndex.DIRECTION_CALLER);
              assertThat(e.path()).isEqualTo(CONTROLLER);
              assertThat(e.line()).isEqualTo(7);
            })
        .anySatisfy(
            e -> {
              assertThat(e.direction()).isEqualTo(CallSiteIndex.DIRECTION_CALLER);
              assertThat(e.path()).isEqualTo(REPOSITORY);
            });
    // 无关包不入选（未 import 变更类型 → 不是候选文件）
    assertThat(index.entries()).noneSatisfy(e -> assertThat(e.path()).isEqualTo(UNRELATED));
    assertThat(index.notes()).noneMatch(n -> n.contains("调用链不完整"));
    String rendered = index.render();
    assertThat(rendered)
        .contains("（含 import 关联）")
        .contains("[caller] " + CONTROLLER)
        .contains("[self] " + SERVICE)
        .contains("词法级符号匹配")
        .contains("DI/运行期装配还原");
  }

  @Test
  void candidateFileCapIsDeclaredWhenImportersExceedBound() {
    Map<String, String> files = new java.util.LinkedHashMap<>();
    files.put(SERVICE, SERVICE_TEXT);
    List<TypeImport> imports = new java.util.ArrayList<>();
    for (int i = 0; i < 70; i++) {
      String path = "src/main/java/com/demo/web/Ctl" + String.format("%02d", i) + ".java";
      files.put(
          path,
          "package com.demo.web;\nimport com.demo.service.UserService;\n"
              + "class Ctl"
              + i
              + " { boolean x(UserService s, java.time.Duration a) { return s.shouldKeepAlive(a); } }\n");
      imports.add(
          new TypeImport(path, "com.demo.service.UserService", "UserService", false, 2, SERVICE));
    }

    CallSiteIndex index = buildCrossPackage(files, imports);

    assertThat(index.candidateFiles()).isEqualTo(CallSiteIndex.MAX_FILES);
    assertThat(index.callerCandidates()).isEqualTo(70);
    assertThat(index.notes()).anyMatch(n -> n.contains("候选文件上限 60"));
    assertThat(index.render()).contains("候选文件上限 60");
  }

  @Test
  void withoutSemanticIndexFallsBackToSameDirectoryAndDeclaresIncompleteChain() {
    Map<String, String> files = Map.of(SERVICE, SERVICE_TEXT, CONTROLLER, CONTROLLER_TEXT);

    CallSiteIndex index =
        CallSiteIndex.build(
            SERVICE,
            List.of(scored("f_1", FindingCategory.BUG, "shouldKeepAlive 未判空")),
            null,
            List.copyOf(files.keySet()),
            path -> Optional.ofNullable(files.get(path)));

    assertThat(index.available()).isTrue();
    assertThat(index.importsAvailable()).isFalse();
    assertThat(index.notes()).anyMatch(n -> n.contains("调用链不完整"));
    assertThat(index.entries()).noneSatisfy(e -> assertThat(e.path()).isEqualTo(CONTROLLER));
    assertThat(index.render()).contains("（同目录）").contains("调用链不完整");
  }

  @Test
  void unavailableWithoutRepoTreeOrSymbols() {
    CallSiteIndex noTree =
        CallSiteIndex.build(
            FILE,
            List.of(scored("f_1", FindingCategory.BUG, "x")),
            diff(),
            List.of(),
            p -> Optional.empty());
    assertThat(noTree.available()).isFalse();
    assertThat(noTree.reason()).isEqualTo("repo-tree-unavailable");
    assertThat(noTree.render()).contains("索引不可得").contains("followup_file");

    // 符号不在被改动文件文本中 → 无符号可索引
    CandidateFinding neutral =
        new CandidateFinding(
            "f_2",
            "run_1",
            FindingCategory.BUG,
            "边界处理可能不完整",
            "逻辑可能有问题",
            "触发条件不明；错误结果不明",
            FILE,
            307,
            Side.RIGHT,
            "L307: 某处逻辑可能有问题",
            "",
            Severity.IMPORTANT,
            false,
            0.5,
            "bug-hunter/boundary",
            "glm-x");
    CallSiteIndex noSymbols =
        CallSiteIndex.build(
            FILE,
            List.of(
                new FindingAggregator.Scored(
                    neutral, "fp", org.cra.common.finding.AnchorStatus.INLINE, null)),
            diff(),
            List.of(FILE),
            path -> Optional.of(TEXT_BUFFER));
    assertThat(noSymbols.available()).isFalse();
    assertThat(noSymbols.reason()).isEqualTo("no-symbols");
  }

  @Test
  void nonBugBatchProducesNoSymbols() {
    CallSiteIndex index =
        CallSiteIndex.build(
            FILE,
            List.of(scored("f_1", FindingCategory.TEST, "getTextBuffer 无测试")),
            diff(),
            List.of(FILE),
            path -> Optional.of(TEXT_BUFFER));
    assertThat(index.available()).isFalse();
    assertThat(index.reason()).isEqualTo("no-symbols");
  }

  @Test
  void renderingCapsEntriesAndDeclaresIncompleteness() {
    StringBuilder big = new StringBuilder();
    big.append("package com.demo.util;\npublic class Big {\n");
    for (int i = 1; i <= 60; i++) {
      big.append("  // filler ").append(i).append(" getTextBuffer\n");
    }
    big.append("}\n");
    Map<String, String> files =
        Map.of(FILE, TEXT_BUFFER, "src/main/java/com/demo/util/Big.java", big.toString());
    CallSiteIndex index =
        CallSiteIndex.build(
            FILE,
            List.of(scored("f_1", FindingCategory.BUG, "L307: getTextBuffer 返回 null")),
            diff(),
            List.copyOf(files.keySet()),
            path -> Optional.ofNullable(files.get(path)));
    assertThat(index.available()).isTrue();
    assertThat(index.entries()).hasSize(CallSiteIndex.MAX_ENTRIES);
    assertThat(index.render()).contains("已截断");
  }

  @Test
  void renderUsesSmallerCapForJudgeCases() {
    Map<String, String> files = Map.of(FILE, TEXT_BUFFER);
    CallSiteIndex index =
        CallSiteIndex.build(
            FILE,
            List.of(scored("f_1", FindingCategory.BUG, "L307: getTextBuffer 返回 null")),
            diff(),
            List.copyOf(files.keySet()),
            path -> Optional.ofNullable(files.get(path)));
    String judgeView = index.render(200);
    assertThat(TokenMath.estimateTokens(judgeView)).isLessThanOrEqualTo(260);
  }
}
