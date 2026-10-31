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
    Selection selection = selectCandidates(candidates, options, notes);
    int skipped = selection.skipped();

    // 第一趟：定义 + import + 包名（内存只保留结构化结果，不缓存源码）
    DefinitionScan scan = scanDefinitions(repository, selection.selected(), notes);
    skipped += scan.readFailures();

    // import 解析：仓库内可唯一定位才填 resolvedPath（外部类型保持 null，不假装知道）
    List<TypeImport> imports =
        scan.rawImports().stream()
            .map(
                raw ->
                    raw.withResolvedPath(
                        resolveImport(raw, scan.typePathsByName(), scan.packageByPath())))
            .toList();

    // 第二趟：只统计索引内已定义符号名的出现（未定义标识符 = 局部/外部，不作跨文件证据）
    Set<String> definedNames = new TreeSet<>();
    Map<String, List<String>> definitionPathsByName = new TreeMap<>();
    Map<String, Integer> definitionsPerPath = new TreeMap<>();
    for (SymbolDefinition definition : scan.definitions()) {
      definedNames.add(definition.name());
      definitionPathsByName
          .computeIfAbsent(definition.name(), key -> new ArrayList<>())
          .add(definition.path());
      definitionsPerPath.merge(definition.path(), 1, Integer::sum);
    }
    ReferenceScan references =
        collectReferences(
            repository,
            scan.scannedPaths(),
            scan.candidateByPath(),
            definedNames,
            definitionPathsByName);
    List<SymbolReferences> referenceList =
        references.occurrencesByName().entrySet().stream()
            .map(
                entry ->
                    new SymbolReferences(
                        entry.getKey(),
                        entry.getValue(),
                        references.sitesByName().getOrDefault(entry.getKey(), List.of())))
            .toList();
    List<FileReferenceStats> fileStats =
        scan.scannedPaths().stream()
            .map(
                path ->
                    new FileReferenceStats(
                        path,
                        definitionsPerPath.getOrDefault(path, 0),
                        references.outboundByPath().getOrDefault(path, 0),
                        references.inboundByPath().getOrDefault(path, 0)))
            .toList();
    return new SymbolIndexSnapshot(
        true,
        headSha,
        "",
        scan.scannedPaths().size(),
        skipped,
        selection.truncated(),
        notes,
        scan.definitions(),
        referenceList,
        imports,
        fileStats);
  }

  /**
   * 按 maxFiles/maxFileBytes 从候选集选本轮扫描集（超限明细如实进 notes）。
   *
   * @param candidates 全部 Java 候选（路径升序）
   * @param options 索引选项
   * @param notes 覆盖声明
   * @return 选择结果
   */
  private static Selection selectCandidates(
      List<Candidate> candidates, SymbolIndexOptions options, List<String> notes) {
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
      } else {
        selected.add(candidate);
      }
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
    return new Selection(selected, skipped, truncated);
  }

  /**
   * 第一趟扫描：逐文件读 blob 并抽取定义/import/包名（读取失败如实计数）。
   *
   * @param repository 仓库
   * @param selected 本轮扫描集
   * @param notes 覆盖声明
   * @return 第一趟结果
   */
  private static DefinitionScan scanDefinitions(
      Repository repository, List<Candidate> selected, List<String> notes) {
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
    return new DefinitionScan(
        scannedPaths,
        candidateByPath,
        packageByPath,
        typePathsByName,
        definitions,
        rawImports,
        readFailures);
  }

  /**
   * 第二趟扫描：只统计索引内已定义符号名的出现与引用位置。
   *
   * @param repository 仓库
   * @param scannedPaths 第一趟成功扫描的路径
   * @param candidateByPath 路径 → 候选
   * @param definedNames 索引内已定义符号名
   * @param definitionPathsByName 符号名 → 定义路径
   * @return 引用统计
   */
  private static ReferenceScan collectReferences(
      Repository repository,
      List<String> scannedPaths,
      Map<String, Candidate> candidateByPath,
      Set<String> definedNames,
      Map<String, List<String>> definitionPathsByName) {
    Map<String, Integer> occurrencesByName = new TreeMap<>();
    Map<String, List<SymbolReferenceSite>> sitesByName = new TreeMap<>();
    Map<String, Set<String>> siteFilesByName = new TreeMap<>();
    Map<String, Integer> outboundByPath = new TreeMap<>();
    Map<String, Integer> inboundByPath = new TreeMap<>();
    for (String path : scannedPaths) {
      Candidate candidate = candidateByPath.get(path);
      String text = candidate == null ? null : readBlob(repository, candidate.blobId());
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
        addReferenceSites(sites, files, path, scan, entry.getValue());
      }
    }
    return new ReferenceScan(occurrencesByName, sitesByName, outboundByPath, inboundByPath);
  }

  /**
   * 追加单个符号在本文件的引用位置（受单符号位置/文件上限约束）。
   *
   * @param sites 该符号已收集的位置
   * @param files 该符号已覆盖的文件
   * @param path 当前文件
   * @param scan 当前文件扫描结果
   * @param lines 当前文件命中行
   */
  private static void addReferenceSites(
      List<SymbolReferenceSite> sites,
      Set<String> files,
      String path,
      JavaSourceScanner.FileScan scan,
      List<Integer> lines) {
    for (int line : lines) {
      boolean newFile = !files.contains(path);
      if (sites.size() >= MAX_SITES_PER_SYMBOL
          || (newFile && files.size() >= MAX_FILES_PER_SYMBOL)) {
        break;
      }
      if (newFile) {
        files.add(path);
      }
      sites.add(
          new SymbolReferenceSite(path, line, JavaSourceScanner.snippet(scan.sourceLines(), line)));
    }
  }

  /** 树内 Java 候选（路径升序；每项只带 blob id 与大小，不缓存内容）。 */
  private record Candidate(String path, ObjectId blobId, long size) {}

  /**
   * 候选选择结果。
   *
   * @param selected 本轮扫描集
   * @param skipped 超长跳过数
   * @param truncated 是否因 maxFiles 截断
   */
  private record Selection(List<Candidate> selected, int skipped, boolean truncated) {}

  /**
   * 第一趟扫描结果。
   *
   * @param scannedPaths 成功扫描路径
   * @param candidateByPath 路径 → 候选
   * @param packageByPath 路径 → 包名
   * @param typePathsByName 类型名 → 定义路径
   * @param definitions 符号定义
   * @param rawImports 原始 import
   * @param readFailures 读取失败数
   */
  private record DefinitionScan(
      List<String> scannedPaths,
      Map<String, Candidate> candidateByPath,
      Map<String, String> packageByPath,
      Map<String, List<String>> typePathsByName,
      List<SymbolDefinition> definitions,
      List<TypeImport> rawImports,
      int readFailures) {}

  /**
   * 第二趟扫描结果。
   *
   * @param occurrencesByName 符号名 → 出现次数
   * @param sitesByName 符号名 → 引用位置
   * @param outboundByPath 文件 → 出向引用数
   * @param inboundByPath 定义文件 → 入向引用数
   */
  private record ReferenceScan(
      Map<String, Integer> occurrencesByName,
      Map<String, List<SymbolReferenceSite>> sitesByName,
      Map<String, Integer> outboundByPath,
      Map<String, Integer> inboundByPath) {}

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
          if (path.endsWith(".java")
              && !underSkippedDirectory(path)
              && (includes.isEmpty() || matchesAny(includes, path))
              && (excludes.isEmpty() || !matchesAny(excludes, path))) {
            addCandidate(repository, treeWalk, path, candidates);
          }
        }
      }
    }
    candidates.sort(Comparator.comparing(Candidate::path));
    return candidates;
  }

  /**
   * 追加单个 Java 候选（对象不可读时跳过，不中断枚举）。
   *
   * @param repository 仓库
   * @param treeWalk 树遍历
   * @param path 文件路径
   * @param candidates 候选集
   */
  private static void addCandidate(
      Repository repository, TreeWalk treeWalk, String path, List<Candidate> candidates) {
    try {
      long size = repository.getObjectDatabase().open(treeWalk.getObjectId(0)).getSize();
      candidates.add(new Candidate(path, treeWalk.getObjectId(0).copy(), size));
    } catch (IOException e) {
      // 对象不可读：与既有 continue 语义一致，跳过该候选
    }
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
