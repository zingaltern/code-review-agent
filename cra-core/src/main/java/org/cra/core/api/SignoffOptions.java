package org.cra.core.api;

import java.util.List;
import java.util.Objects;

/**
 * 签字建议配置（05§5 {@code agents.signoff}；07§5 M4a）：开关、可信作者与分级路径规则。
 *
 * <p>路径规则为内置默认（M4a 固定，可由构造方替换做实验）；低/中/高分级与签字人数 固定为 0/1/2（RiskTier），不做任意配置——分级表是设计契约（01§3.4）。
 *
 * @param enabled 是否生成签字建议（false 时 Summary 不出签字段，评审其余行为不变）
 * @param trustedAuthors 可信作者登录名（大小写不敏感；低风险 0 人签的前提之一；空 = 无任何人可信）
 * @param highRiskPaths 核心高危路径 glob（安全/支付/鉴权等，命中建议 2 人签）
 * @param criticalConfigPaths 难恢复的关键配置 glob（部署/迁移/生产配置，命中建议 2 人签）
 * @param systemPromptPaths 系统提示词路径 glob（命中原建议 1 人签）
 * @param docPaths 纯文档路径 glob（全量命中是低风险候选）
 * @param featureFlagPaths 功能开关配置路径 glob（全量命中且仅开关行变更是低风险候选）
 */
public record SignoffOptions(
    boolean enabled,
    List<String> trustedAuthors,
    List<String> highRiskPaths,
    List<String> criticalConfigPaths,
    List<String> systemPromptPaths,
    List<String> docPaths,
    List<String> featureFlagPaths) {

  /** 核心高危路径默认集（01§3.4"核心高危路径"的工程形态）。 */
  public static final List<String> DEFAULT_HIGH_RISK_PATHS =
      List.of(
          "**/auth/**",
          "**/security/**",
          "**/crypto/**",
          "**/payment/**",
          "**/billing/**",
          "**/*Auth*.java",
          "**/*Security*.java",
          "**/*Crypto*.java",
          "**/*Token*.java",
          "**/*Password*.java",
          "**/*Secret*.java",
          "**/*Permission*.java");

  /** 难恢复关键配置默认集（部署/基础设施/数据库迁移/生产配置）。 */
  public static final List<String> DEFAULT_CRITICAL_CONFIG_PATHS =
      List.of(
          ".github/workflows/**",
          "**/Dockerfile*",
          "**/docker-compose*.yml",
          "**/docker-compose*.yaml",
          "**/*.tf",
          "**/k8s/**",
          "**/kubernetes/**",
          "**/helm/**",
          "**/deploy/**",
          "**/infra/**",
          "**/migrations/**",
          "**/db/migration/**",
          "**/*.sql",
          "**/application-prod*.yml",
          "**/application-prod*.yaml",
          "**/application-prod*.properties");

  /** 系统提示词默认集（01§3.4 分级表"系统提示词 → 1 人"）。 */
  public static final List<String> DEFAULT_SYSTEM_PROMPT_PATHS =
      List.of("**/prompts/**", "**/prompt/**", "**/system-prompts/**", "**/*.prompt");

  /** 纯文档默认集（低风险候选；REVIEW.md 另行按评审策略变更处理）。 */
  public static final List<String> DEFAULT_DOC_PATHS =
      List.of(
          "docs/**",
          "**/*.md",
          "**/*.mdx",
          "**/*.txt",
          "**/*.adoc",
          "**/*.rst",
          "LICENSE*",
          "NOTICE*",
          "CHANGELOG*");

  /** 功能开关配置默认集（低风险候选；仅当变更行全部是开关行时成立）。 */
  public static final List<String> DEFAULT_FEATURE_FLAG_PATHS =
      List.of(
          "**/application*.yml",
          "**/application*.yaml",
          "**/application*.properties",
          "**/features/**",
          "**/feature-flags/**",
          "**/*.properties");

  public SignoffOptions {
    trustedAuthors = List.copyOf(trustedAuthors == null ? List.of() : trustedAuthors);
    highRiskPaths = List.copyOf(highRiskPaths == null ? List.of() : highRiskPaths);
    criticalConfigPaths =
        List.copyOf(criticalConfigPaths == null ? List.of() : criticalConfigPaths);
    systemPromptPaths = List.copyOf(systemPromptPaths == null ? List.of() : systemPromptPaths);
    docPaths = List.copyOf(docPaths == null ? List.of() : docPaths);
    featureFlagPaths = List.copyOf(featureFlagPaths == null ? List.of() : featureFlagPaths);
  }

  /**
   * 默认配置（05§5：enabled=true、trusted-authors 空、路径规则为内置默认）。
   *
   * @param trustedAuthors 可信作者登录名
   * @return 默认配置
   */
  public static SignoffOptions defaults(List<String> trustedAuthors) {
    return of(true, trustedAuthors);
  }

  /**
   * 按开关与可信作者构造（路径规则取内置默认）。
   *
   * @param enabled 是否生成签字建议
   * @param trustedAuthors 可信作者登录名
   * @return 配置
   */
  public static SignoffOptions of(boolean enabled, List<String> trustedAuthors) {
    return new SignoffOptions(
        enabled,
        trustedAuthors,
        DEFAULT_HIGH_RISK_PATHS,
        DEFAULT_CRITICAL_CONFIG_PATHS,
        DEFAULT_SYSTEM_PROMPT_PATHS,
        DEFAULT_DOC_PATHS,
        DEFAULT_FEATURE_FLAG_PATHS);
  }

  /**
   * 全默认配置（无可信作者）。
   *
   * @return 默认配置
   */
  public static SignoffOptions defaults() {
    return defaults(List.of());
  }

  /**
   * 关闭形态（不生成签字建议；评审其余行为不变）。
   *
   * @return disabled 配置
   */
  public static SignoffOptions disabled() {
    SignoffOptions defaults = defaults();
    return new SignoffOptions(
        false,
        defaults.trustedAuthors(),
        defaults.highRiskPaths(),
        defaults.criticalConfigPaths(),
        defaults.systemPromptPaths(),
        defaults.docPaths(),
        defaults.featureFlagPaths());
  }

  /**
   * 作者是否在可信名单（大小写不敏感；空名单恒 false）。
   *
   * @param author PR 作者登录名
   * @return true 若命中
   */
  public boolean isTrustedAuthor(String author) {
    if (author == null || author.isBlank()) {
      return false;
    }
    return trustedAuthors.stream()
        .filter(Objects::nonNull)
        .anyMatch(candidate -> candidate.strip().equalsIgnoreCase(author.strip()));
  }
}
