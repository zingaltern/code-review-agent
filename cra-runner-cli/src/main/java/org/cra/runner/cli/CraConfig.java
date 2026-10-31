package org.cra.runner.cli;

import java.util.Locale;
import org.cra.agents.api.AgentBudgetOverrides;
import org.cra.common.exception.CraConfigException;
import org.cra.common.run.ReviewMode;

/**
 * CLI 运行配置（05§5 部署级配置的 M2 子集；值来源 = 命令行参数 + 环境变量）。GitHub token 可经 `--token`/`GITHUB_TOKEN` 提供；LLM 与
 * GitLab 密钥只从环境变量/文件读（05§3/§6）。组装期校验：缺项/非法值即 CraConfigException → 退出码 2。
 *
 * <p>构造入口是 {@link #builder()}：42 个组件的 record 位置参数散落在调用点既无法审阅，新增字段也无处收敛（JLS 8.10.4 要求 canonical
 * 构造器可见性不低于 record 本身，故无法声明为 private——约定改为位置参数只出现在 {@link Builder#build()}）。默认值住在 Builder
 * 字段初值；空值归一与校验仍在紧凑构造器（唯一校验点，不因入口变化而搬家）。
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

  /** push 基线解析默认回溯步数（05§2.4）。 */
  public static final int DEFAULT_PUSH_LOOKBACK = 20;

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
   * 空 Builder（默认值 = 05§5 文档默认与 M2 形态的兼容语义：CrossChecker 关闭、PR 发布形态、github 平台）。
   *
   * @return 新建 Builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * 以本配置为初值的 Builder：只覆盖目标组件，其余原样保留（回填不再逐字段复制 42 项）。
   *
   * @return 预填当前全部组件的 Builder
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /** 本地身份/区间解析回填（remote origin 推导与 base/head sha 解析完成后调用）。 */
  public CraConfig withResolvedLocal(
      String owner, String name, String resolvedBase, String resolvedHead) {
    return toBuilder()
        .repoOwner(owner)
        .repoName(name)
        .baseSha(resolvedBase)
        .headSha(resolvedHead)
        .build();
  }

  /**
   * push 直推 base 解析回填（M4c：上一完成 commit / 可配置基线，05§2.4）。
   *
   * @param resolvedBase 解析出的基线 sha
   * @return 回填后的配置
   */
  public CraConfig withResolvedPushBase(String resolvedBase) {
    return toBuilder().baseSha(resolvedBase).build();
  }

  /** 逐字段构建（setter 名与 record 组件一一对应；未设置的组件取 Builder 默认）。 */
  public static final class Builder {

    private String repoOwner;
    private String repoName;
    private int prNumber;
    private String baseSha;
    private String headSha;
    private String depth;
    private boolean dryRun;
    private boolean securityBlock;
    private String githubToken;
    private String githubApi;
    private String llmApiKey;
    private String llmBaseUrl;
    private String llmModel;
    private String runRoot;
    private String workspace;
    private int largeDiffLines;
    private int hardCapLines;
    private int nitBudget;
    private long runTotalTokens;
    private double degradeAtFirst;
    private double degradeAtSecond;
    private String publishUnverified;
    private int spotCheckPercent;
    private boolean local;
    private String execCommand;
    private String ledgerDb;
    private String llmModelStrong;
    private String llmModelLight;
    private String fallbackChain;
    private String rpm;
    private boolean crossCheckerEnabled;
    private String crossCheckerModes = "assumption,sniff";
    private int crossCheckerHistoryWeeks = 8;
    private String llmTemperature;
    private int pushLookback = DEFAULT_PUSH_LOOKBACK;
    private ReviewMode reviewMode = ReviewMode.PULL_REQUEST;
    private SignoffConfig signoff;
    private String platform;
    private GitLabSettings gitlab;
    private SemanticContextConfig semanticContext;
    private boolean includeTestFiles;
    private AgentBudgetOverrides agentBudgets;

    private Builder() {}

    /** 以既有配置预填全部组件（{@link CraConfig#toBuilder()} 的唯一实现）。 */
    private Builder(CraConfig source) {
      this.repoOwner = source.repoOwner();
      this.repoName = source.repoName();
      this.prNumber = source.prNumber();
      this.baseSha = source.baseSha();
      this.headSha = source.headSha();
      this.depth = source.depth();
      this.dryRun = source.dryRun();
      this.securityBlock = source.securityBlock();
      this.githubToken = source.githubToken();
      this.githubApi = source.githubApi();
      this.llmApiKey = source.llmApiKey();
      this.llmBaseUrl = source.llmBaseUrl();
      this.llmModel = source.llmModel();
      this.runRoot = source.runRoot();
      this.workspace = source.workspace();
      this.largeDiffLines = source.largeDiffLines();
      this.hardCapLines = source.hardCapLines();
      this.nitBudget = source.nitBudget();
      this.runTotalTokens = source.runTotalTokens();
      this.degradeAtFirst = source.degradeAtFirst();
      this.degradeAtSecond = source.degradeAtSecond();
      this.publishUnverified = source.publishUnverified();
      this.spotCheckPercent = source.spotCheckPercent();
      this.local = source.local();
      this.execCommand = source.execCommand();
      this.ledgerDb = source.ledgerDb();
      this.llmModelStrong = source.llmModelStrong();
      this.llmModelLight = source.llmModelLight();
      this.fallbackChain = source.fallbackChain();
      this.rpm = source.rpm();
      this.crossCheckerEnabled = source.crossCheckerEnabled();
      this.crossCheckerModes = source.crossCheckerModes();
      this.crossCheckerHistoryWeeks = source.crossCheckerHistoryWeeks();
      this.llmTemperature = source.llmTemperature();
      this.pushLookback = source.pushLookback();
      this.reviewMode = source.reviewMode();
      this.signoff = source.signoff();
      this.platform = source.platform();
      this.gitlab = source.gitlab();
      this.semanticContext = source.semanticContext();
      this.includeTestFiles = source.includeTestFiles();
      this.agentBudgets = source.agentBudgets();
    }

    public Builder repoOwner(String value) {
      this.repoOwner = value;
      return this;
    }

    public Builder repoName(String value) {
      this.repoName = value;
      return this;
    }

    public Builder prNumber(int value) {
      this.prNumber = value;
      return this;
    }

    public Builder baseSha(String value) {
      this.baseSha = value;
      return this;
    }

    public Builder headSha(String value) {
      this.headSha = value;
      return this;
    }

    public Builder depth(String value) {
      this.depth = value;
      return this;
    }

    public Builder dryRun(boolean value) {
      this.dryRun = value;
      return this;
    }

    public Builder securityBlock(boolean value) {
      this.securityBlock = value;
      return this;
    }

    public Builder githubToken(String value) {
      this.githubToken = value;
      return this;
    }

    public Builder githubApi(String value) {
      this.githubApi = value;
      return this;
    }

    public Builder llmApiKey(String value) {
      this.llmApiKey = value;
      return this;
    }

    public Builder llmBaseUrl(String value) {
      this.llmBaseUrl = value;
      return this;
    }

    public Builder llmModel(String value) {
      this.llmModel = value;
      return this;
    }

    public Builder runRoot(String value) {
      this.runRoot = value;
      return this;
    }

    public Builder workspace(String value) {
      this.workspace = value;
      return this;
    }

    public Builder largeDiffLines(int value) {
      this.largeDiffLines = value;
      return this;
    }

    public Builder hardCapLines(int value) {
      this.hardCapLines = value;
      return this;
    }

    public Builder nitBudget(int value) {
      this.nitBudget = value;
      return this;
    }

    public Builder runTotalTokens(long value) {
      this.runTotalTokens = value;
      return this;
    }

    public Builder degradeAtFirst(double value) {
      this.degradeAtFirst = value;
      return this;
    }

    public Builder degradeAtSecond(double value) {
      this.degradeAtSecond = value;
      return this;
    }

    public Builder publishUnverified(String value) {
      this.publishUnverified = value;
      return this;
    }

    public Builder spotCheckPercent(int value) {
      this.spotCheckPercent = value;
      return this;
    }

    public Builder local(boolean value) {
      this.local = value;
      return this;
    }

    public Builder execCommand(String value) {
      this.execCommand = value;
      return this;
    }

    public Builder ledgerDb(String value) {
      this.ledgerDb = value;
      return this;
    }

    public Builder llmModelStrong(String value) {
      this.llmModelStrong = value;
      return this;
    }

    public Builder llmModelLight(String value) {
      this.llmModelLight = value;
      return this;
    }

    public Builder fallbackChain(String value) {
      this.fallbackChain = value;
      return this;
    }

    public Builder rpm(String value) {
      this.rpm = value;
      return this;
    }

    public Builder crossCheckerEnabled(boolean value) {
      this.crossCheckerEnabled = value;
      return this;
    }

    public Builder crossCheckerModes(String value) {
      this.crossCheckerModes = value;
      return this;
    }

    public Builder crossCheckerHistoryWeeks(int value) {
      this.crossCheckerHistoryWeeks = value;
      return this;
    }

    public Builder llmTemperature(String value) {
      this.llmTemperature = value;
      return this;
    }

    public Builder pushLookback(int value) {
      this.pushLookback = value;
      return this;
    }

    public Builder reviewMode(ReviewMode value) {
      this.reviewMode = value;
      return this;
    }

    public Builder signoff(SignoffConfig value) {
      this.signoff = value;
      return this;
    }

    public Builder platform(String value) {
      this.platform = value;
      return this;
    }

    public Builder gitlab(GitLabSettings value) {
      this.gitlab = value;
      return this;
    }

    public Builder semanticContext(SemanticContextConfig value) {
      this.semanticContext = value;
      return this;
    }

    public Builder includeTestFiles(boolean value) {
      this.includeTestFiles = value;
      return this;
    }

    public Builder agentBudgets(AgentBudgetOverrides value) {
      this.agentBudgets = value;
      return this;
    }

    /**
     * 构造配置（空值归一与校验由 record 紧凑构造器完成——唯一校验点）。
     *
     * @return 不可变配置
     */
    public CraConfig build() {
      return new CraConfig(
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
          crossCheckerEnabled,
          crossCheckerModes,
          crossCheckerHistoryWeeks,
          llmTemperature,
          pushLookback,
          reviewMode,
          signoff,
          platform,
          gitlab,
          semanticContext,
          includeTestFiles,
          agentBudgets);
    }
  }
}
