package org.cra.runner.cli;

/**
 * CLI 运行配置（05§5 部署级配置的 M0 子集；值来源 = 命令行参数 + 环境变量，密钥只从环境变量读， 05§6）。组装期校验：缺项即 CraConfigException → 退出码
 * 2（05§3）。
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
    String runRoot) {}
