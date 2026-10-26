package org.cra.common.signoff;

/**
 * 单次评审的签字上下文（07§5 M4a）：PR 作者与测试状态是低风险 0 人签的前置条件， 由运行时提供，不进入 LLM 提示词。
 *
 * @param author PR 作者登录名（空 = 未知；未知作者不满足可信作者条件）
 * @param testsStatus 测试状态（默认 UNKNOWN）
 */
public record SignoffContext(String author, TestsStatus testsStatus) {

  public SignoffContext {
    author = author == null ? "" : author.strip();
    testsStatus = testsStatus == null ? TestsStatus.UNKNOWN : testsStatus;
  }

  /**
   * 未知上下文（CI 未提供作者/测试状态时的默认）。
   *
   * @return author 为空、测试状态 UNKNOWN
   */
  public static SignoffContext unknown() {
    return new SignoffContext("", TestsStatus.UNKNOWN);
  }

  /**
   * 作者是否已知。
   *
   * @return true 若 author 非空白
   */
  public boolean authorKnown() {
    return !author.isBlank();
  }
}
