package org.cra.runner.cli;

import java.util.Locale;
import org.cra.agents.api.AgentBudgetOverrides;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;
import org.cra.llm.api.LlmGatewayConfig;

/**
 * CLI 运行配置（05§5 部署级配置的 M2 子集；值来源 = 命令行参数 + 环境变量）。GitHub token 可经 `--token`/`GITHUB_TOKEN` 提供；LLM 与
 * GitLab 密钥只从环境变量/文件读（05§3/§6）。组装期校验：缺项/非法值即 CraConfigException → 退出码 2。
 *
 * @param repoOwner 仓库属主（本地模式可为 null，由 remote origin 推导后经 {@link #withResolvedLocal} 回填）
 * @param repoName 仓库名（同上）
 * @param prNumber PR 编号（本地模式为 0，02§5 本地形态）
 * @param baseSha 基线 sha（CI）/基线 ref 名（本地模式，默认 main，命令执行期解析为 sha）
 * @param headSha 目标 sha（CI）/null（本地模式 = HEAD，命令执行期解析）
 * @param depth 深度档位
 * @param dryRun dry-run（全程零写，05§6；本地模式默认 true，05§3）
 * @param securityBlock --security-block：存在 security finding 时退出码 3（05§3）
 * @param githubToken GitHub token（GITHUB_TOKEN 或 --token；本地与 GitLab 模式为 null）
 * @param githubApi GitHub API 根（默认 https://api.github.com）
 * @param llmApiKey LLM 密钥（CRA_LLM_API_KEY；本地模式缺省 = 跳过评审并如实声明）
 * @param llmBaseUrl LLM 端点（CRA_LLM_BASE_URL）
 * @param llmModel 模型名（CRA_LLM_MODEL = standard 档，05§5）
 * @param runRoot trace/brief 落盘根（默认 .cra/runs，05§4 artifact 路径）
 * @param workspace 本地工作区根（jgit blame 用；CI = checkout 目录，默认当前目录；05§2.1）
 * @param largeDiffLines 大 diff 降档线（review.large-diff-lines，默认 4000；原 2000，#64 放宽）
 * @param hardCapLines 硬上限（review.hard-cap-lines，默认 16000；原 8000，#64 放宽）
 * @param nitBudget nit 预算默认（review.nit-budget-default，默认 3；REVIEW.md 显式配置优先）
 * @param runTotalTokens run 级预算（agents.budget.run-total-tokens，默认 800k；原 500k，#64 放宽）
 * @param degradeAtFirst 第一降档阈值（agents.budget.degrade-at[0]，默认 0.70）
 * @param degradeAtSecond 第二降档阈值（agents.budget.degrade-at[1]，默认 0.85）
 * @param publishUnverified UNVERIFIED 发布策略（review.publish-unverified：never|labeled；M2 起 labeled 为带
 *     UNVERIFIED 标签发布的生效路径，02§4.2）
 * @param spotCheckPercent important/security 级 REFUTED 抽查保险丝百分比（agents.verifier.
 *     important-refuted-spotcheck 的百分数形态，默认 20，03§4.3）
 * @param local 本地 pre-PR 模式（--local；05§3）
 * @param execCommand 本地实跑命令（--exec，默认 {@code mvn -B -ntp verify}；07 T2.10）
 * @param ledgerDb 本地 SQLite 账本文件（--ledger-db，默认 ~/.cra/ledger.db；02§5 本地形态）
 * @param llmModelStrong strong 档模型名（--model-strong / CRA_LLM_MODEL_STRONG；缺省继承 standard）
 * @param llmModelLight light 档模型名（--model-light / CRA_LLM_MODEL_LIGHT；缺省继承 standard）
 * @param fallbackChain 降级链（--fallback-chain / CRA_LLM_FALLBACK_CHAIN，默认 standard,light；05§5）
 * @param rpm per-model RPM（--rpm / CRA_LLM_RPM，形如 standard=60,strong=30,light=60；默认 60/30/60）
 * @param llmMaxConcurrency LLM 并发信号量上限（CRA_LLM_MAX_CONCURRENCY，默认 8，1..64；有效并发 = min(配置值,
 *     线程池宽度)，09§6）
 * @param llmTemperature 采样温度（--llm-temperature / CRA_LLM_TEMPERATURE；空 = 不传沿用 provider 默认）
 * @param crossCheckerEnabled CrossChecker 总开关（05§5 agents.crosschecker.enabled；--crosschecker /
 *     CRA_CROSSCHECKER_ENABLED，默认 false——关闭不动 Standard 基线算术，04§2.2）
 * @param crossCheckerModes 模式开关（--crosschecker-modes / CRA_CROSSCHECKER_MODES，默认 assumption,sniff）
 * @param crossCheckerHistoryWeeks 上下游变更史回溯周数（--crosschecker-history-weeks /
 *     CRA_CROSSCHECKER_HISTORY_WEEKS，默认 8）
 * @param reviewMode 发布形态（M4c --push = COMMIT；M4d sweep = ISSUE；默认 PULL_REQUEST，05§2.2）
 * @param pushLookback push 模式沿首父链回溯查找"上一完成 commit"的最大步数（--lookback，默认 20，05§2.4）
 * @param signoff 签字建议配置（05§5 agents.signoff；M4a；默认开启、作者/测试状态未知）
 * @param platform Git 平台（`github`|`gitlab`；M4e 第二半，--platform / CRA_GIT_PLATFORM，默认 github）
 * @param gitlab GitLab 连接设置（仅 platform=gitlab 时生效；密钥只从环境/文件读，05§6）
 * @param semanticContext 语义上下文（L2 跨文件切片）配置（M4e，ADR-0014；默认关闭）
 * @param includeTestFiles 测试文件是否进入评审范围（02§4.4；`--review-tests` / `CRA_REVIEW_TESTS`，默认 false）
 * @param agentBudgets 单代理预算覆盖（05§5 agents.budget.&lt;role&gt;.*；空 = 各定义默认，03§6）
 */
public record CraConfig(
    String repoOwner,
    String repoName,
    int prNumber,
    String baseSha,
    String headSha,
    String depth,
    boolean dryRun,
    boolean securityBlock,
    String githubToken,
    String githubApi,
    String llmApiKey,
    String llmBaseUrl,
    String llmModel,
    String runRoot,
    String workspace,
    int largeDiffLines,
    int hardCapLines,
    int nitBudget,
    long runTotalTokens,
    double degradeAtFirst,
    double degradeAtSecond,
    String publishUnverified,
    int spotCheckPercent,
    boolean local,
    String execCommand,
    String ledgerDb,
    String llmModelStrong,
    String llmModelLight,
    String fallbackChain,
    String rpm,
    int llmMaxConcurrency,
    boolean crossCheckerEnabled,
    String crossCheckerModes,
    int crossCheckerHistoryWeeks,
    String llmTemperature,
    int pushLookback,
    ReviewMode reviewMode,
    SignoffConfig signoff,
    String platform,
    GitLabSettings gitlab,
    SemanticContextConfig semanticContext,
    boolean includeTestFiles,
    AgentBudgetOverrides agentBudgets) {

  /** 本地 trace/brief 落盘根默认（05§4 artifact 路径）。 */
  public static final String DEFAULT_RUN_ROOT = ".cra/runs";

  /** 本地 SQLite 账本文件默认（02§5 本地形态）。 */
  public static final String DEFAULT_LEDGER_DB = "~/.cra/ledger.db";

  /** LLM 降级链默认（05§5）。 */
  public static final String DEFAULT_FALLBACK_CHAIN = "standard,light";

  public CraConfig {
    signoff = signoff == null ? SignoffConfig.defaults() : signoff;
    semanticContext = semanticContext == null ? SemanticContextConfig.defaults() : semanticContext;
    agentBudgets = agentBudgets == null ? AgentBudgetOverrides.none() : agentBudgets;
    platform =
        platform == null || platform.isBlank()
            ? "github"
            : platform.strip().toLowerCase(Locale.ROOT);
    if (!"github".equals(platform) && !"gitlab".equals(platform)) {
      throw new CraConfigException("platform 非法: " + platform + "（github|gitlab）");
    }
    gitlab = gitlab == null ? GitLabSettings.none() : gitlab;
  }

  /** 是否 GitLab 平台（M4e 第二半）。 */
  public boolean isGitlab() {
    return "gitlab".equals(platform);
  }

  /**
   * M4c 前形态兼容构造（发布形态 = PR）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param prNumber PR 编号
   * @param baseSha 基线 sha
   * @param headSha 目标 sha
   * @param depth 深度档位
   * @param dryRun dry-run
   * @param securityBlock --security-block
   * @param githubToken GitHub token
   * @param githubApi GitHub API 根
   * @param llmApiKey LLM 密钥
   * @param llmBaseUrl LLM 端点
   * @param llmModel 模型名
   * @param runRoot trace 落盘根
   * @param workspace 本地工作区根
   * @param largeDiffLines 大 diff 降档线
   * @param hardCapLines 硬上限
   * @param nitBudget nit 预算默认
   * @param runTotalTokens run 级预算
   * @param degradeAtFirst 第一降档阈值
   * @param degradeAtSecond 第二降档阈值
   * @param publishUnverified UNVERIFIED 发布策略
   * @param spotCheckPercent 抽查保险丝百分比
   * @param local 本地 pre-PR 模式
   * @param execCommand 本地实跑命令
   * @param ledgerDb 本地账本
   * @param llmModelStrong strong 档模型
   * @param llmModelLight light 档模型
   * @param fallbackChain 降级链
   * @param rpm per-model RPM
   * @param crossCheckerEnabled CrossChecker 总开关
   * @param crossCheckerModes 模式开关
   * @param crossCheckerHistoryWeeks 变更史回溯周数
   * @param llmTemperature 采样温度
   */
  public CraConfig(
      String repoOwner,
      String repoName,
      int prNumber,
      String baseSha,
      String headSha,
      String depth,
      boolean dryRun,
      boolean securityBlock,
      String githubToken,
      String githubApi,
      String llmApiKey,
      String llmBaseUrl,
      String llmModel,
      String runRoot,
      String workspace,
      int largeDiffLines,
      int hardCapLines,
      int nitBudget,
      long runTotalTokens,
      double degradeAtFirst,
      double degradeAtSecond,
      String publishUnverified,
      int spotCheckPercent,
      boolean local,
      String execCommand,
      String ledgerDb,
      String llmModelStrong,
      String llmModelLight,
      String fallbackChain,
      String rpm,
      boolean crossCheckerEnabled,
      String crossCheckerModes,
      int crossCheckerHistoryWeeks,
      String llmTemperature) {
    this(
        repoOwner,
        repoName,
        prNumber,
        baseSha,
        headSha,
        depth,
        dryRun,
        securityBlock,
        githubToken,
        githubApi,
        llmApiKey,
        llmBaseUrl,
        llmModel,
        runRoot,
        workspace,
        largeDiffLines,
        hardCapLines,
        nitBudget,
        runTotalTokens,
        degradeAtFirst,
        degradeAtSecond,
        publishUnverified,
        spotCheckPercent,
        local,
        execCommand,
        ledgerDb,
        llmModelStrong,
        llmModelLight,
        fallbackChain,
        rpm,
        LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY,
        crossCheckerEnabled,
        crossCheckerModes,
        crossCheckerHistoryWeeks,
        llmTemperature,
        DEFAULT_PUSH_LOOKBACK,
        ReviewMode.PULL_REQUEST,
        SignoffConfig.defaults(),
        "github",
        GitLabSettings.none(),
        SemanticContextConfig.defaults(),
        false,
        AgentBudgetOverrides.none());
  }

  /** push 基线解析默认回溯步数（05§2.4）。 */
  public static final int DEFAULT_PUSH_LOOKBACK = 20;

  /**
   * M2 形态兼容构造（CrossChecker 关闭，05§5 agents.crosschecker.enabled=false）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param prNumber PR 编号
   * @param baseSha 基线 sha
   * @param headSha 目标 sha
   * @param depth 深度档位
   * @param dryRun dry-run
   * @param securityBlock --security-block
   * @param githubToken GitHub token
   * @param githubApi GitHub API 根
   * @param llmApiKey LLM 密钥
   * @param llmBaseUrl LLM 端点
   * @param llmModel 模型名
   * @param runRoot trace 落盘根
   * @param workspace 本地工作区根
   * @param largeDiffLines 大 diff 降档线
   * @param hardCapLines 硬上限
   * @param nitBudget nit 预算默认
   * @param runTotalTokens run 级预算
   * @param degradeAtFirst 第一降档阈值
   * @param degradeAtSecond 第二降档阈值
   * @param publishUnverified UNVERIFIED 发布策略
   * @param spotCheckPercent 抽查保险丝百分比
   * @param local 本地 pre-PR 模式
   * @param execCommand 本地实跑命令
   * @param ledgerDb 本地账本
   * @param llmModelStrong strong 档模型
   * @param llmModelLight light 档模型
   * @param fallbackChain 降级链
   * @param rpm per-model RPM
   */
  public CraConfig(
      String repoOwner,
      String repoName,
      int prNumber,
      String baseSha,
      String headSha,
      String depth,
      boolean dryRun,
      boolean securityBlock,
      String githubToken,
      String githubApi,
      String llmApiKey,
      String llmBaseUrl,
      String llmModel,
      String runRoot,
      String workspace,
      int largeDiffLines,
      int hardCapLines,
      int nitBudget,
      long runTotalTokens,
      double degradeAtFirst,
      double degradeAtSecond,
      String publishUnverified,
      int spotCheckPercent,
      boolean local,
      String execCommand,
      String ledgerDb,
      String llmModelStrong,
      String llmModelLight,
      String fallbackChain,
      String rpm) {
    this(
        repoOwner,
        repoName,
        prNumber,
        baseSha,
        headSha,
        depth,
        dryRun,
        securityBlock,
        githubToken,
        githubApi,
        llmApiKey,
        llmBaseUrl,
        llmModel,
        runRoot,
        workspace,
        largeDiffLines,
        hardCapLines,
        nitBudget,
        runTotalTokens,
        degradeAtFirst,
        degradeAtSecond,
        publishUnverified,
        spotCheckPercent,
        local,
        execCommand,
        ledgerDb,
        llmModelStrong,
        llmModelLight,
        fallbackChain,
        rpm,
        LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY,
        false,
        "assumption,sniff",
        8,
        null,
        DEFAULT_PUSH_LOOKBACK,
        ReviewMode.PULL_REQUEST,
        SignoffConfig.defaults(),
        "github",
        GitLabSettings.none(),
        SemanticContextConfig.defaults(),
        false,
        AgentBudgetOverrides.none());
  }

  /** 本地身份/区间解析回填（remote origin 推导与 base/head sha 解析完成后调用）。 */
  public CraConfig withResolvedLocal(
      String owner, String name, String resolvedBase, String resolvedHead) {
    return new CraConfig(
        owner,
        name,
        prNumber(),
        resolvedBase,
        resolvedHead,
        depth(),
        dryRun(),
        securityBlock(),
        githubToken(),
        githubApi(),
        llmApiKey(),
        llmBaseUrl(),
        llmModel(),
        runRoot(),
        workspace(),
        largeDiffLines(),
        hardCapLines(),
        nitBudget(),
        runTotalTokens(),
        degradeAtFirst(),
        degradeAtSecond(),
        publishUnverified(),
        spotCheckPercent(),
        local(),
        execCommand(),
        ledgerDb(),
        llmModelStrong(),
        llmModelLight(),
        fallbackChain(),
        rpm(),
        llmMaxConcurrency(),
        crossCheckerEnabled(),
        crossCheckerModes(),
        crossCheckerHistoryWeeks(),
        llmTemperature(),
        pushLookback(),
        reviewMode(),
        signoff(),
        platform(),
        gitlab(),
        semanticContext(),
        includeTestFiles(),
        agentBudgets());
  }

  /**
   * push 直推 base 解析回填（M4c：上一完成 commit / 可配置基线，05§2.4）。
   *
   * @param resolvedBase 解析出的基线 sha
   * @return 回填后的配置
   */
  public CraConfig withResolvedPushBase(String resolvedBase) {
    return new CraConfig(
        repoOwner(),
        repoName(),
        prNumber(),
        resolvedBase,
        headSha(),
        depth(),
        dryRun(),
        securityBlock(),
        githubToken(),
        githubApi(),
        llmApiKey(),
        llmBaseUrl(),
        llmModel(),
        runRoot(),
        workspace(),
        largeDiffLines(),
        hardCapLines(),
        nitBudget(),
        runTotalTokens(),
        degradeAtFirst(),
        degradeAtSecond(),
        publishUnverified(),
        spotCheckPercent(),
        local(),
        execCommand(),
        ledgerDb(),
        llmModelStrong(),
        llmModelLight(),
        fallbackChain(),
        rpm(),
        llmMaxConcurrency(),
        crossCheckerEnabled(),
        crossCheckerModes(),
        crossCheckerHistoryWeeks(),
        llmTemperature(),
        pushLookback(),
        reviewMode(),
        signoff(),
        platform(),
        gitlab(),
        semanticContext(),
        includeTestFiles(),
        agentBudgets());
  }
}
