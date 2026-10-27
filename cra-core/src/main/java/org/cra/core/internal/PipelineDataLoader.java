package org.cra.core.internal;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.Side;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.RepoRef;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.context.api.ReviewPolicyParser;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.SemanticContextOptions;
import org.cra.core.internal.pipeline.ChunkPlanner;
import org.cra.core.internal.pipeline.CrossCheckEvents;
import org.cra.core.internal.pipeline.CrossCheckHistory;
import org.cra.core.internal.pipeline.DepthPlan;
import org.cra.core.internal.pipeline.SemanticContext;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.api.LocalRepoContext;
import org.cra.git.api.SymbolIndex;

/**
 * 流水线数据面（T1.4/T2.7，04§3.2/03§5.2）：REVIEW.md 策略加载、head 全文预取、增量 diff、文件内容缓存与锚定判定。
 *
 * <p>从 {@link ReviewPipeline} 拆出：平台读取与纯判定集中在此，编排器只消费结果；单文件/策略读取失败按既有口径降级， 初始 diff 取数失败仍上抛由 run 级收口。
 */
final class PipelineDataLoader {

  static final String REVIEW_MD = "REVIEW.md";

  private final GitPlatformAdapter adapter;
  private final ReviewPolicyParser policyParser;
  private final LocalRepoContext localRepoContext;
  private final CrossCheckerOptions crossCheckerOptions;
  private final EventHistoryQuery eventHistoryQuery;
  private final SemanticContextOptions semanticContextOptions;
  private final SymbolIndex symbolIndex;
  private final Clock clock;

  PipelineDataLoader(
      GitPlatformAdapter adapter,
      ReviewPolicyParser policyParser,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions) {
    this(
        adapter,
        policyParser,
        localRepoContext,
        crossCheckerOptions,
        null,
        SemanticContextOptions.disabled(),
        null,
        Clock.systemUTC());
  }

  /**
   * @param eventHistoryQuery 事件史查询端口（M4f-full；null = 未装配，CrossChecker 声明事件史不可得）
   * @param clock 事件检索窗口锚点（测试可固定）
   */
  PipelineDataLoader(
      GitPlatformAdapter adapter,
      ReviewPolicyParser policyParser,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions,
      EventHistoryQuery eventHistoryQuery,
      Clock clock) {
    this(
        adapter,
        policyParser,
        localRepoContext,
        crossCheckerOptions,
        eventHistoryQuery,
        SemanticContextOptions.disabled(),
        null,
        clock);
  }

  /**
   * @param semanticContextOptions 语义上下文配置（M4e；null = 关闭）
   * @param symbolIndex 符号索引端口（null = 未装配 → 开启时按不可得声明）
   * @param clock 事件检索窗口锚点（测试可固定）
   */
  PipelineDataLoader(
      GitPlatformAdapter adapter,
      ReviewPolicyParser policyParser,
      LocalRepoContext localRepoContext,
      CrossCheckerOptions crossCheckerOptions,
      EventHistoryQuery eventHistoryQuery,
      SemanticContextOptions semanticContextOptions,
      SymbolIndex symbolIndex,
      Clock clock) {
    this.adapter = adapter;
    this.policyParser = policyParser;
    this.localRepoContext = localRepoContext;
    this.crossCheckerOptions = crossCheckerOptions;
    this.eventHistoryQuery = eventHistoryQuery;
    this.semanticContextOptions =
        semanticContextOptions == null ? SemanticContextOptions.disabled() : semanticContextOptions;
    this.symbolIndex = symbolIndex;
    this.clock = clock;
  }

  DiffResult fetchDiff(ReviewRequest request) {
    return adapter.fetchDiff(
        new RepoRef(request.repoOwner(), request.repoName()), request.baseSha(), request.headSha());
  }

  /**
   * 构建语义上下文（M4e，ADR-0014）：默认关闭；quick 档不触发（02§4.3）；构建失败/无本地仓库/超限 → 不可得 + 声明，不炸 run。
   *
   * <p>关闭时零副作用（不构建索引、不发 trace、不产生声明）——reviewer brief 与开关引入前逐字节一致（ADR-0014 决策 5）。
   *
   * @param request 评审请求（head 为索引锚点）
   * @param depthPlan 深度计划（quick 不触发）
   * @param trace trace 写入器（仅启用时记录 {@code semantic_context} 事件）
   * @return 语义上下文
   */
  SemanticContext buildSemanticContext(
      ReviewRequest request, DepthPlan depthPlan, TraceWriter trace) {
    if (!semanticContextOptions.enabled()) {
      return SemanticContext.disabled();
    }
    if (depthPlan.effectiveDepth() == Depth.QUICK) {
      SemanticContext quick =
          SemanticContext.of(
              semanticContextOptions,
              SymbolIndexSnapshot.unavailable(
                  request.headSha(), "quick 档不触发（02§4.3；语义上下文仅 standard/deep）"));
      trace.emit("semantic_context", quick.traceFields());
      return quick;
    }
    SymbolIndexSnapshot snapshot;
    if (symbolIndex == null) {
      snapshot =
          SymbolIndexSnapshot.unavailable(
              request.headSha(), "语义索引未装配（组合根未注入 SymbolIndex）；L2 切片按不可得声明");
    } else {
      snapshot = symbolIndex.build(request.headSha(), semanticContextOptions.indexOptions());
    }
    SemanticContext context = SemanticContext.of(semanticContextOptions, snapshot);
    trace.emit("semantic_context", context.traceFields());
    return context;
  }

  /**
   * 查询上下游路径集近 N 周变更史（排除本 PR 自身 commit——变更本身不是外部证据）。
   *
   * <p>quick 档不查询（02§4.3 档位联动：CrossChecker 不触发）；无本地仓库时降级为空历史 + 声明（05§2.1 同 blame 模式）。
   */
  CrossCheckHistory resolveCrossCheckHistory(
      ReviewRequest request, DiffResult diff, ChunkPlanner.Plan plan, DepthPlan depthPlan) {
    return resolveCrossCheckHistory(request, diff, plan, depthPlan, Set.of());
  }

  /**
   * 查询上下游路径集近 N 周变更史（语义索引增强路径集，03§3.1 输入 b；ADR-0014 决策 8）。
   *
   * @param extraPaths 索引增强的关联路径（定义/引用/import 关联；空集 = 仅变更文件）
   */
  CrossCheckHistory resolveCrossCheckHistory(
      ReviewRequest request,
      DiffResult diff,
      ChunkPlanner.Plan plan,
      DepthPlan depthPlan,
      Set<String> extraPaths) {
    if (!crossCheckerOptions.enabled() || depthPlan.effectiveDepth() == Depth.QUICK) {
      return CrossCheckHistory.none();
    }
    Set<String> paths = new LinkedHashSet<>();
    plan.chunks().forEach(chunk -> chunk.files().forEach(file -> paths.add(file.path())));
    boolean indexAssisted = extraPaths != null && !extraPaths.isEmpty();
    if (indexAssisted) {
      paths.addAll(extraPaths);
    }
    Set<String> prCommits =
        diff.commits().stream().map(CommitSummary::sha).collect(Collectors.toSet());
    return CrossCheckHistoryAssembler.assemble(
        localRepoContext,
        paths,
        crossCheckerOptions.historyWeeks(),
        request.headSha(),
        prCommits,
        indexAssisted);
  }

  /**
   * 查询与变更路径/commit/关键词相关的产品事件史（M4f-full，ADR-0012）。
   *
   * <p>窗口与变更史同源（historyWeeks）；quick 档不查询（CrossChecker 不触发）。未装配端口 / 检索失败 → available=false +
   * 声明，不静默当作"已检查且无矛盾"。
   *
   * @param history 已组装的变更史（其 commit 也作为事件检索面）
   * @return 事件史 + 声明
   */
  CrossCheckEvents resolveCrossCheckEvents(
      ReviewRequest request,
      DiffResult diff,
      ChunkPlanner.Plan plan,
      DepthPlan depthPlan,
      CrossCheckHistory history) {
    if (!crossCheckerOptions.enabled() || depthPlan.effectiveDepth() == Depth.QUICK) {
      return CrossCheckEvents.none();
    }
    Set<String> paths = new LinkedHashSet<>();
    plan.chunks().forEach(chunk -> chunk.files().forEach(file -> paths.add(file.path())));
    Set<String> commits = new LinkedHashSet<>();
    diff.commits().forEach(commit -> commits.add(commit.sha()));
    commits.add(request.headSha());
    if (history != null) {
      history.entries().forEach(entry -> commits.add(entry.sha()));
    }
    List<String> messages = diff.commits().stream().map(CommitSummary::message).toList();
    String repo = request.repoOwner() + "/" + request.repoName();
    return CrossCheckEventAssembler.assemble(
        eventHistoryQuery,
        repo,
        paths,
        commits,
        messages,
        crossCheckerOptions.historyWeeks(),
        clock);
  }

  /** REVIEW.md 一律从 base SHA 读取（04§3.2 规则 1）；读取失败（404）视为未配置；解析容错降级最小默认策略 + 警告（规则 4）。 */
  /**
   * 测试文件清单采集（v1.3.2 test 类分型）：head 侧一次 listFiles。
   *
   * <p>背景（M3 取证）：test 类 finding 主张「某行为无测试覆盖」——负向/全局主张，验证上下文 不含测试目录时无法证否 → 必然 INSUFFICIENT → Judge
   * 按证据不足拒（L3 实测 judge 53 拒中 48 条为此）。清单供验证者核对「是否已有覆盖」，未列出的行为可 followup 补查。
   *
   * @param request 评审请求（head sha 为清单锚点）
   * @param trace trace 写入器（test_index 事件如实记录可得性）
   * @return 测试文件清单（失败降级为空列表 + 事件声明）
   */
  List<String> testFileIndex(ReviewRequest request, TraceWriter trace) {
    try {
      List<String> files =
          adapter.listFiles(
              new RepoRef(request.repoOwner(), request.repoName()),
              request.headSha(),
              "**/test/**/*.java");
      trace.emit("test_index", Map.of("available", true, "files", files.size()));
      return files;
    } catch (RuntimeException e) {
      trace.emit(
          "test_index",
          Map.of(
              "available",
              false,
              "reason",
              e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      return List.of();
    }
  }

  ReviewPolicy loadPolicy(TraceWriter trace, DiffResult diff, ReviewRequest request) {
    String markdown;
    try {
      FileContent content =
          adapter.readFile(
              new RepoRef(request.repoOwner(), request.repoName()), request.baseSha(), REVIEW_MD);
      markdown = content.binary() ? null : content.text();
    } catch (CraIntegrationException e) {
      trace.emit("policy_load", Map.of("present", false, "reason", "read-failed"));
      return ReviewPolicy.empty();
    }
    ReviewPolicy policy = policyParser.parse(markdown);
    trace.emit(
        "policy_load",
        Map.of(
            "present", policy.present(),
            "modified_in_pr", hasReviewMdChange(diff),
            "warnings", policy.warnings()));
    return policy;
  }

  /** head 侧全文预取（cra-context 不发起平台调用，10§2）；不可读文件以 binary 占位（text=null）——指纹与 L1 都按不可得降级。 */
  Map<String, FileContent> prefetchHeadFiles(ReviewRequest request, ChunkPlanner.Plan plan) {
    Map<String, FileContent> headFiles = new HashMap<>();
    RepoRef repo = new RepoRef(request.repoOwner(), request.repoName());
    for (ChunkPlanner.Chunk chunk : plan.chunks()) {
      for (DiffFile file : chunk.files()) {
        if (headFiles.containsKey(file.path()) || "removed".equals(file.status())) {
          continue;
        }
        try {
          headFiles.put(file.path(), adapter.readFile(repo, request.headSha(), file.path()));
        } catch (RuntimeException e) {
          headFiles.put(file.path(), new FileContent(file.path(), null, true));
        }
      }
    }
    return headFiles;
  }

  /** T2.7 diff-only 复审的增量 diff（03§5.2）：取自上一轮完成 head；取不到（force-push/接口失败）回退全量并声明。 */
  DiffResult incrementDiff(
      ReviewRequest request,
      TraceWriter trace,
      String lastHead,
      DiffResult fallback,
      List<String> earlyDegradations) {
    try {
      return adapter.fetchDiff(
          new RepoRef(request.repoOwner(), request.repoName()), lastHead, request.headSha());
    } catch (RuntimeException e) {
      trace.emit(
          "convergence",
          Map.of(
              "note", "increment-diff-unavailable",
              "last_head", ReviewBodyRenderer.shortSha(lastHead),
              "error", String.valueOf(e.getMessage())));
      earlyDegradations.add("increment-diff-fallback-full");
      return fallback;
    }
  }

  /**
   * 文件内容缓存读取（新代码与旧代码共用，key = path|sha）：二进制/不可得为 empty——空内容锚仍确定性， 该 finding 走 possible_duplicate
   * 分支而非崩溃（09§4 降级写状态）。
   */
  Optional<String> contentOf(
      String path, String sha, ReviewRequest request, Map<String, Optional<String>> cache) {
    return cache.computeIfAbsent(
        path + "|" + sha,
        k -> {
          try {
            FileContent file =
                adapter.readFile(new RepoRef(request.repoOwner(), request.repoName()), sha, path);
            return file.binary() || file.text() == null
                ? Optional.empty()
                : Optional.of(file.text());
          } catch (RuntimeException e) {
            return Optional.empty();
          }
        });
  }

  /**
   * 账本标记的发布时锚行内容哈希（按标记 commit_sha 取旧文件内容重算——滑窗匹配键，02§4.1 M1 实现口径）。 旧内容不可得（force-push/删除）时为
   * empty：该标记不参与滑窗，仅保留 exact 与 possible_duplicate。
   */
  Optional<String> oldAnchorHashOf(
      LedgerFindingMarker marker, ReviewRequest request, Map<String, Optional<String>> cache) {
    return contentOf(marker.file(), marker.commitSha(), request, cache)
        .map(
            text ->
                FingerprintCalculator.sha256(
                    FingerprintDeduper.lineAt(List.of(text.split("\n", -1)), marker.line())
                        .strip()));
  }

  static AnchorStatus anchorStatus(DiffResult diff, CandidateFinding finding) {
    if (!Side.RIGHT.equals(finding.side())) {
      return AnchorStatus.SUMMARY_FALLBACK;
    }
    Optional<DiffFile> file =
        diff.files().stream().filter(f -> f.path().equals(finding.file())).findFirst();
    return file.map(
            f ->
                f.containsLine(Side.RIGHT, finding.line())
                    ? AnchorStatus.INLINE
                    : AnchorStatus.SUMMARY_FALLBACK)
        .orElse(AnchorStatus.SUMMARY_FALLBACK);
  }

  static boolean hasReviewMdChange(DiffResult diff) {
    return diff.files().stream().anyMatch(f -> REVIEW_MD.equals(f.path()));
  }
}
