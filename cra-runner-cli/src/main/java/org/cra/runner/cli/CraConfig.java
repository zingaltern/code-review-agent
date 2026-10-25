package org.cra.runner.cli;

/**
 * CLI 运行配置（05§5 部署级配置的 M1 子集；值来源 = 命令行参数 + 环境变量，密钥只从环境变量读， 05§6）。组装期校验：缺项/非法值即 CraConfigException →
 * 退出码 2（05§3）。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 * @param depth 深度档位
 * @param dryRun dry-run（全程零写，05§6）
 * @param securityBlock --security-block：存在 security finding 时退出码 3
 * @param githubToken GitHub token（GITHUB_TOKEN 或 --token）
 * @param githubApi GitHub API 根（默认 https://api.github.com）
 * @param llmApiKey LLM 密钥（CRA_LLM_API_KEY）
 * @param llmBaseUrl LLM 端点（CRA_LLM_BASE_URL）
 * @param llmModel 模型名（CRA_LLM_MODEL）
 * @param runRoot trace/brief 落盘根（默认 .cra/runs，05§4 artifact 路径）
 * @param workspace 本地工作区根（jgit blame 用；CI = checkout 目录，默认当前目录；05§2.1）
 * @param largeDiffLines 大 diff 降档线（review.large-diff-lines，默认 2000）
 * @param hardCapLines 硬上限（review.hard-cap-lines，默认 8000）
 * @param nitBudget nit 预算默认（review.nit-budget-default，默认 3；REVIEW.md 显式配置优先）
 * @param runTotalTokens run 级预算（agents.budget.run-total-tokens，默认 500k）
 * @param degradeAtFirst 第一降档阈值（agents.budget.degrade-at[0]，默认 0.70）
 * @param degradeAtSecond 第二降档阈值（agents.budget.degrade-at[1]，默认 0.85）
 * @param publishUnverified UNVERIFIED 发布策略（review.publish-unverified：never|labeled；M1 无验证层，labeled
 *     仅声明不生效）
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
    String publishUnverified) {}
