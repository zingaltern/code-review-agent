package org.cra.git.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.cra.common.semantic.FileReferenceStats;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexOptions;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolKind;
import org.cra.common.semantic.SymbolReferenceSite;
import org.cra.common.semantic.SymbolReferences;
import org.cra.common.semantic.TypeImport;
import org.cra.common.text.Texts;
import org.cra.git.api.SymbolIndex;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * 默认语义索引实现（ADR-0014 决策 1/3）：jgit 读 head 树 + {@link JavaSourceScanner} 词法/结构抽取。
 *
 * <p>确定性来自三点：树读取（不读工作区脏文件）、路径升序 + 固定上限、快照列表按固定键排序。两趟扫描：第一趟收集定义/import（及类型名→文件），
 * 第二趟只统计"索引内已定义符号名"的出现——未定义的标识符（局部变量、外部库 API）不入引用表，避免把 grep 噪声当跨文件证据。
 *
 * <p>上限（防炸内存/耗时）：maxFiles（默认 2000）、maxFileBytes（默认 256 KiB）、每符号 ≤25 处位置且 ≤10 文件、每文件每名 ≤100 行。 超限一律进
 * notes（覆盖声明可见），不静默丢弃。
 */
public final class JgitSymbolIndex implements SymbolIndex {

  /** 内置跳过目录（第一版固定：版本库元数据、构建产物、依赖缓存）。 */
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          "target",
          "node_modules",
          "build",
          "out",
          ".gradle",
          ".idea",
          ".settings",
          ".mvn",
          "dist",
          "vendor");

  /** 单符号位置上限（证据条数；occurrences 计数不受影响）。 */
  static final int MAX_SITES_PER_SYMBOL = 25;

  /** 单符号位置覆盖文件上限。 */
  static final int MAX_FILES_PER_SYMBOL = 10;

  /** notes 中逐条列出的跳过文件名上限（其余只报计数）。 */
  private static final int MAX_LISTED_SKIPS = 10;

  /** Java-only 覆盖声明（每次可用构建都带，覆盖声明不得静默）。 */
  static final String JAVA_ONLY_NOTE = "语义索引第一版仅支持 Java 源文件（非 .java 文件跳过，不做多语言抽取）";

  private final Path root;

  /**
   * @param candidateRoot 候选仓库根（null 视为不可得；非 git checkout 同样不可得）
   */
  public JgitSymbolIndex(Path candidateRoot) {
    this.root = candidateRoot;
  }

  @Override
  public SymbolIndexSnapshot build(String headSha, SymbolIndexOptions options) {
    SymbolIndexOptions opts = options == null ? SymbolIndexOptions.defaults() : options;
    if (root == null) {
      return SymbolIndexSnapshot.unavailable(headSha, "无本地工作区配置；语义索引不可得");
    }
    if (headSha == null || headSha.isBlank()) {
      return SymbolIndexSnapshot.unavailable(headSha, "head sha 为空；语义索引不可得");
    }
    // .git 可以是目录（普通 clone）或文件（git worktree / submodule 的 gitdir 指针）
    if (!Files.exists(root.resolve(".git"))) {
      return SymbolIndexSnapshot.unavailable(headSha, "本地工作区不是 git checkout（无 .git）；语义索引不可得");
    }
    try (Repository repository = new FileRepositoryBuilder().setWorkTree(root.toFile()).build()) {
      ObjectId commitId = repository.resolve(headSha);
      if (commitId == null) {
        return SymbolIndexSnapshot.unavailable(headSha, "head 不可解析: " + headSha);
      }
      return buildFromTree(repository, commitId, headSha, opts);
    } catch (IOException | RuntimeException e) {
      // 05§2.1 降级语义同 blame：索引不可得不炸 run，消费方如实声明（ADR-0014 决策 6）
      return SymbolIndexSnapshot.unavailable(
          headSha, "语义索引构建失败（" + e.getClass().getSimpleName() + "）；L2 切片按不可得声明");
    }
  }

  private SymbolIndexSnapshot buildFromTree(
      Repository repository, ObjectId commitId, String headSha, SymbolIndexOptions options)
      throws IOException {
    List<Candidate> candidates = collectJavaSources(repository, commitId, options);
    if (options.overLimitPolicy() == SymbolIndexOptions.OverLimitPolicy.UNAVAILABLE
        && candidates.size() > options.maxFiles()) {
      return SymbolIndexSnapshot.unavailable(
          headSha,
          "Java 文件数 "
              + candidates.size()
              + " 超过上限 "
              + options.maxFiles()
              + "（超限策略 UNAVAILABLE）；语义索引不可得");
    }

    List<String> notes = new ArrayList<>();
    notes.add(JAVA_ONLY_NOTE);
    List<String> skipDetails = new ArrayList<>();
    List<Candidate> selected = new ArrayList<>();
    int skipped = 0;
    boolean truncated = false;
    for (Candidate candidate : candidates) {
      if (selected.size() >= options.maxFiles()) {
        truncated = true;
        break;
      }
      if (candidate.size() > options.maxFileBytes()) {
        skipped++;
        if (skipDetails.size() < MAX_LISTED_SKIPS) {
          skipDetails.add(
              "跳过超长文件 "
                  + candidate.path()
                  + "（"
                  + candidate.size()
                  + " 字节 > 上限 "
                  + options.maxFileBytes()
                  + "）");
        }
        continue;
      }
      selected.add(candidate);
    }
    notes.addAll(skipDetails);
    if (skipped > skipDetails.size()) {
      notes.add("另有 " + (skipped - skipDetails.size()) + " 个超长文件跳过（明细省略）");
    }
    if (truncated) {
      notes.add(
          "扫描上限：命中 "
              + candidates.size()
              + " 个 Java 文件，仅扫描前 "
              + selected.size()
              + " 个（maxFiles="
              + options.maxFiles()
              + "，按路径升序截断）");
    }

    // 第一趟：定义 + import + 包名（内存只保留结构化结果，不缓存源码）
    Map<String, String> packageByPath = new TreeMap<>();
    Map<String, List<String>> typePathsByName = new TreeMap<>();
    List<SymbolDefinition> definitions = new ArrayList<>();
    List<TypeImport> rawImports = new ArrayList<>();
    List<String> scannedPaths = new ArrayList<>();
    Map<String, Candidate> candidateByPath = new TreeMap<>();
    int readFailures = 0;
    for (Candidate candidate : selected) {
      String text = readBlob(repository, candidate.blobId());
      if (text == null) {
        skipped++;
        readFailures++;
        if (readFailures <= MAX_LISTED_SKIPS) {
          notes.add("读取失败跳过 " + candidate.path());
        }
        continue;
      }
      candidateByPath.put(candidate.path(), candidate);
      JavaSourceScanner.FileScan scan = JavaSourceScanner.scan(candidate.path(), text);
      scannedPaths.add(candidate.path());
      packageByPath.put(candidate.path(), scan.packageName());
      definitions.addAll(scan.definitions());
      rawImports.addAll(scan.imports());
      for (SymbolDefinition definition : scan.definitions()) {
        if (definition.kind() == SymbolKind.TYPE) {
          typePathsByName
              .computeIfAbsent(definition.name(), key -> new ArrayList<>())
              .add(definition.path());
        }
      }
    }
    if (readFailures > 0) {
      notes.add("读取失败 " + readFailures + " 个文件（已从扫描集剔除）");
    }

    // import 解析：仓库内可唯一定位才填 resolvedPath（外部类型保持 null，不假装知道）
    List<TypeImport> imports = new ArrayList<>();
    for (TypeImport raw : rawImports) {
      imports.add(raw.withResolvedPath(resolveImport(raw, typePathsByName, packageByPath)));
    }

    // 第二趟：只统计索引内已定义符号名的出现（未定义标识符 = 局部/外部，不作跨文件证据）
    Set<String> definedNames = new TreeSet<>();
    Map<String, List<String>> definitionPathsByName = new TreeMap<>();
    Map<String, Integer> definitionsPerPath = new TreeMap<>();
    for (SymbolDefinition definition : definitions) {
      definedNames.add(definition.name());
      definitionPathsByName
          .computeIfAbsent(definition.name(), key -> new ArrayList<>())
          .add(definition.path());
      definitionsPerPath.merge(definition.path(), 1, Integer::sum);
    }
    Map<String, Integer> occurrencesByName = new TreeMap<>();
    Map<String, List<SymbolReferenceSite>> sitesByName = new TreeMap<>();
    Map<String, Set<String>> siteFilesByName = new TreeMap<>();
    Map<String, Integer> outboundByPath = new TreeMap<>();
    Map<String, Integer> inboundByPath = new TreeMap<>();
    for (String path : scannedPaths) {
      Candidate candidate = candidateByPath.get(path);
      if (candidate == null) {
        continue;
      }
      String text = readBlob(repository, candidate.blobId());
      if (text == null) {
        continue;
      }
      JavaSourceScanner.FileScan scan = JavaSourceScanner.scan(path, text);
      for (Map.Entry<String, List<Integer>> entry : scan.identifierLines().entrySet()) {
        String name = entry.getKey();
        if (!definedNames.contains(name)) {
          continue;
        }
        int count =
            Math.max(entry.getValue().size(), scan.identifierCounts().getOrDefault(name, 0));
        occurrencesByName.merge(name, count, Integer::sum);
        outboundByPath.merge(path, count, Integer::sum);
        for (String definitionPath : definitionPathsByName.getOrDefault(name, List.of())) {
          if (!definitionPath.equals(path)) {
            inboundByPath.merge(definitionPath, count, Integer::sum);
          }
        }
        List<SymbolReferenceSite> sites =
            sitesByName.computeIfAbsent(name, key -> new ArrayList<>());
        Set<String> files = siteFilesByName.computeIfAbsent(name, key -> new LinkedHashSet<>());
        for (int line : entry.getValue()) {
          if (sites.size() >= MAX_SITES_PER_SYMBOL) {
            break;
          }
          boolean newFile = !files.contains(path);
          if (newFile && files.size() >= MAX_FILES_PER_SYMBOL) {
            break;
          }
          if (newFile) {
            files.add(path);
          }
          sites.add(
              new SymbolReferenceSite(
                  path, line, JavaSourceScanner.snippet(scan.sourceLines(), line)));
        }
      }
    }

    List<SymbolReferences> references = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : occurrencesByName.entrySet()) {
      references.add(
          new SymbolReferences(
              entry.getKey(),
              entry.getValue(),
              sitesByName.getOrDefault(entry.getKey(), List.of())));
    }
    List<FileReferenceStats> fileStats = new ArrayList<>();
    for (String path : scannedPaths) {
      fileStats.add(
          new FileReferenceStats(
              path,
              definitionsPerPath.getOrDefault(path, 0),
              outboundByPath.getOrDefault(path, 0),
              inboundByPath.getOrDefault(path, 0)));
    }
    return new SymbolIndexSnapshot(
        true,
        headSha,
        "",
        scannedPaths.size(),
        skipped,
        truncated,
        notes,
        definitions,
        references,
        imports,
        fileStats);
  }

  /** 树内 Java 候选（路径升序；每项只带 blob id 与大小，不缓存内容）。 */
  private record Candidate(String path, ObjectId blobId, long size) {}

  private static List<Candidate> collectJavaSources(
      Repository repository, ObjectId commitId, SymbolIndexOptions options) throws IOException {
    List<Pattern> includes = compile(options.includePatterns());
    List<Pattern> excludes = compile(options.excludePatterns());
    List<Candidate> candidates = new ArrayList<>();
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit commit = walk.parseCommit(commitId);
      try (TreeWalk treeWalk = new TreeWalk(repository)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(true);
        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!path.endsWith(".java") || underSkippedDirectory(path)) {
            continue;
          }
          if (!includes.isEmpty() && !matchesAny(includes, path)) {
            continue;
          }
          if (!excludes.isEmpty() && matchesAny(excludes, path)) {
            continue;
          }
          long size;
          try {
            size = repository.getObjectDatabase().open(treeWalk.getObjectId(0)).getSize();
          } catch (IOException e) {
            continue;
          }
          candidates.add(new Candidate(path, treeWalk.getObjectId(0).copy(), size));
        }
      }
    }
    candidates.sort(Comparator.comparing(Candidate::path));
    return candidates;
  }

  private static List<Pattern> compile(Set<String> globs) {
    List<Pattern> patterns = new ArrayList<>();
    for (String glob : globs) {
      StringBuilder regex = new StringBuilder("^");
      for (char c : glob.toCharArray()) {
        if (c == '*') {
          regex.append(".*");
        } else {
          regex.append(Pattern.quote(String.valueOf(c)));
        }
      }
      patterns.add(Pattern.compile(regex.append('$').toString()));
    }
    return patterns;
  }

  private static boolean matchesAny(List<Pattern> patterns, String path) {
    for (Pattern pattern : patterns) {
      if (pattern.matcher(path).matches()) {
        return true;
      }
    }
    return false;
  }

  private static boolean underSkippedDirectory(String path) {
    for (String segment : path.split("/")) {
      if (SKIPPED_DIRECTORIES.contains(segment)) {
        return true;
      }
    }
    return false;
  }

  /** 读取 blob 文本（二进制 / 非法 UTF-8 返回 null；ADR-0016 严格解码，不产出 U+FFFD 伪文本）。 */
  private static String readBlob(Repository repository, ObjectId blobId) {
    try {
      ObjectLoader loader = repository.getObjectDatabase().open(blobId);
      byte[] bytes = loader.getBytes();
      for (byte value : bytes) {
        if (value == 0) {
          return null;
        }
      }
      return Texts.strictUtf8(bytes);
    } catch (IOException e) {
      return null;
    }
  }

  /** import 解析：仓库内同名类型唯一、或限定名与 package+simpleName 完全一致才解析。 */
  private static String resolveImport(
      TypeImport entry,
      Map<String, List<String>> typePathsByName,
      Map<String, String> packageByPath) {
    if (entry.staticImport() || "*".equals(entry.simpleName()) || entry.simpleName().isBlank()) {
      return null;
    }
    List<String> candidates = typePathsByName.get(entry.simpleName());
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    for (String candidate : candidates) {
      String pkg = packageByPath.getOrDefault(candidate, "");
      String qualified = pkg.isEmpty() ? entry.simpleName() : pkg + "." + entry.simpleName();
      if (qualified.equals(entry.imported())) {
        return candidate;
      }
    }
    return null;
  }
}
