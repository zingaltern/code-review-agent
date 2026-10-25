package org.cra.git.github.api;

import org.cra.common.git.CommentSource;
import org.cra.git.api.GitPlatformAdapter;

/**
 * GitHub 组件集（ADR-0005：同一实现同时充当平台适配器与账本取数端口；组合根把 commentSource 注入 cra-store 的 GitHub 评论账本，把 adapter
 * 注入 core）。
 *
 * @param adapter Git 平台适配器
 * @param commentSource 账本与处置采集取数端口（CommentSource，05§2.1）
 */
public record GitHubComponents(GitPlatformAdapter adapter, CommentSource commentSource) {}
