package org.cra.common.git;

import java.util.List;

/**
 * issue 发布命令（M4d 夜间存量评审，05§2.5；GitPlatformAdapter.postIssueReview 入参）。
 *
 * <p>历史 PR 没有"可回帖的当前 PR 评论流"语义（存量评审面向已合并/已关闭的 PR），因此产物是 issue：每条被评审 PR 一个 issue（载体与账本粒度），正文含
 * Summary 与全部 finding 详情，文末带 run 完成标记（05§1.3）。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param title issue 标题（夜间形态形如 {@code [cra-nightly] PR #42 abc1234 存量评审}）
 * @param body issue 正文（含全部 finding 详情与 run 完成标记）
 * @param labels 标签（可空；标签不存在时平台自行忽略）
 */
public record PostIssueCommand(
    String repoOwner, String repoName, String title, String body, List<String> labels) {

  public PostIssueCommand {
    if (repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
      throw new IllegalArgumentException("repo owner/name must be non-blank");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title must be non-blank");
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body must be non-blank");
    }
    labels = List.copyOf(labels == null ? List.of() : labels);
  }
}
