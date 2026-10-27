package org.cra.git.gitlab.api;

import org.cra.common.git.CommentSource;
import org.cra.git.api.GitPlatformAdapter;

/**
 * GitLab 组件集（ADR-0009：同一实现同时充当平台适配器与账本取数端口；组合根把 commentSource 注入 cra-store 的评论账本，把 adapter 注入
 * core）。
 *
 * @param adapter Git 平台适配器
 * @param commentSource 账本与处置采集取数端口（CommentSource，05§2.1）
 */
public record GitLabComponents(GitPlatformAdapter adapter, CommentSource commentSource) {}
