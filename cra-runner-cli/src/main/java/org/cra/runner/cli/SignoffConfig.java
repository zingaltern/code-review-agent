package org.cra.runner.cli;

import java.util.List;
import org.cra.common.signoff.SignoffContext;
import org.cra.common.signoff.TestsStatus;
import org.cra.core.api.SignoffOptions;

/**
 * 签字建议运行配置（05§5 {@code agents.signoff} 的 CLI 形态；M4a）。
 *
 * <p>作者与测试状态来自命令行/环境（CI 形态默认 unknown——评审组件不假装知道构建结果）； 本地 pre-PR 形态会用实跑退出码覆盖测试状态。路径分级规则取 {@link
 * SignoffOptions} 的内置默认。
 *
 * @param enabled 是否生成签字建议（默认 true）
 * @param trustedAuthors 可信作者登录名（低风险 0 人签前提之一）
 * @param author PR 作者登录名（空 = 未知）
 * @param testsStatus 测试状态文本（passed|failed|unknown，默认 unknown）
 */
record SignoffConfig(
    boolean enabled, List<String> trustedAuthors, String author, String testsStatus) {

  SignoffConfig {
    trustedAuthors = List.copyOf(trustedAuthors == null ? List.of() : trustedAuthors);
    author = author == null ? "" : author.strip();
    testsStatus = testsStatus == null || testsStatus.isBlank() ? "unknown" : testsStatus.strip();
  }

  /**
   * 默认配置（enabled=true、无信任作者、作者/测试状态未知）。
   *
   * @return 默认配置
   */
  static SignoffConfig defaults() {
    return new SignoffConfig(true, List.of(), "", "unknown");
  }

  /**
   * 本次 run 的签字上下文。
   *
   * @return 作者 + 测试状态
   */
  SignoffContext context() {
    return new SignoffContext(author, TestsStatus.parse(testsStatus));
  }

  /**
   * 本次 run 的签字配置。
   *
   * @return SignoffOptions（路径规则内置默认）
   */
  SignoffOptions toOptions() {
    return SignoffOptions.of(enabled, trustedAuthors);
  }
}
