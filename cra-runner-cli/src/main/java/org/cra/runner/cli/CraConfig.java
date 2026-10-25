package org.cra.runner.cli;

/**
 * CLI 运行配置（05§5 部署级配置的 M2 子集；值来源 = 命令行参数 + 环境变量，密钥只从环境变量读， 05§6）。组装期校验：缺项/非法值即 CraConfigException →
 * 退出码 2（05§3）。
 *
 * @param repoOwner 仓库属主（本地模式可为 null，由 remote origin 推导后经 {@link #withResolvedLocal} 回填）
 * @param repoName 仓库名（同上）
 * @param prNumber PR 编号（本地模式为 0，02§5 本地形态）
 * @param baseSha 基线 sha（CI）/基线 ref 名（本地模式，默认 main，命令执行期解析为 sha）
 * @param headSha 目标 sha（CI）/null（本地模式 = HEAD，命令执行期解析）
 * @param depth 深度档位
 * @param dryRun dry-run（全程零写，05§6；本地模式默认 true，05§3）
 * @param securityBlock --security-block：存在 security finding 时退出码 3（05§3）
 * @param githubToken GitHub token（GITHUB_TOKEN 或 --token；本地模式不需要）
 * @param githubApi GitHub API 根（默认 https://api.github.com）
 * @param llmApiKey LLM 密钥（CRA_LLM_API_KEY；本地模式缺省 = 跳过评审并如实声明）
 * @param llmBaseUrl LLM 端点（CRA_LLM_BASE_URL）
 * @param llmModel 模型名（CRA_LLM_MODEL = standard 档，05§5）
 * @param runRoot trace/brief 落盘根（默认 .cra/runs，05§4 artifact 路径）
 * @param workspace 本地工作区根（jgit blame 用；CI = checkout 目录，默认当前目录；05§2.1）
 * @param largeDiffLines 大 diff 降档线（review.large-diff-lines，默认 2000）
 * @param hardCapLines 硬上限（review.hard-cap-lines，默认 8000）
 * @param nitBudget nit 预算默认（review.nit-budget-default，默认 3；REVIEW.md 显式配置优先）
 * @param runTotalTokens run 级预算（agents.budget.run-total-tokens，默认 500k）
 * @param degradeAtFirst 第一降档阈值（agents.budget.degrade-at[0]，默认 0.70）
 * @param degradeAtSecond 第二降档阈值（agents.budget.degrade-at[1]，默认 0.85）
 * @param publishUnverified UNVERIFIED 发布策略（review.publish-unverified：never|labeled）
 * @param local 本地 pre-PR 模式（--local；05§3）
 * @param execCommand 本地实跑命令（--exec，默认 {@code mvn -B -ntp verify}；07 T2.10）
 * @param ledgerDb 本地 SQLite 账本文件（--ledger-db，默认 ~/.cra/ledger.db；02§5 本地形态）
 * @param llmModelStrong strong 档模型名（--model-strong / CRA_LLM_MODEL_STRONG；缺省继承 standard）
 * @param llmModelLight light 档模型名（--model-light / CRA_LLM_MODEL_LIGHT；缺省继承 standard）
 * @param fallbackChain 降级链（--fallback-chain / CRA_LLM_FALLBACK_CHAIN，默认 standard,light；05§5）
 * @param rpm per-model RPM（--rpm / CRA_LLM_RPM，形如 standard=60,strong=30,light=60；默认 60/30/60）
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
    boolean local,
    String execCommand,
    String ledgerDb,
    String llmModelStrong,
    String llmModelLight,
    String fallbackChain,
    String rpm) {

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
        local(),
        execCommand(),
        ledgerDb(),
        llmModelStrong(),
        llmModelLight(),
        fallbackChain(),
        rpm());
  }
}
