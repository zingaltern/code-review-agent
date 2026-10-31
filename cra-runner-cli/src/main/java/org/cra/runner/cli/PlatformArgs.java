package org.cra.runner.cli;

import java.util.Locale;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.llm.api.LlmParsing;

/**
 * Git 平台选择与 GitLab 连接参数解析（M4e 第二半；ADR-0013），兼作 CLI/sweep 的共享解析家（R5）： 仓库标识回退、token 解析（含 GitLab 拒收
 * --token）、LLM env 三键、深度档位与 CrossChecker 模式串在 CliArgs 与 SweepArgs 的重复段收敛于此—— 错误文案保留各命令既有 key 名。
 *
 * <p>平台优先级：`--platform` &gt; `CRA_GIT_PLATFORM` &gt; `github`（默认保持既有行为）。GitLab 的仓库标识、token 与 API
 * 根沿用 GitLab CI 变量；token 只认环境变量/文件，命令行 `--token` 仅属于 GitHub（避免跨平台串用密钥）。
 */
final class PlatformArgs {

  static final String GITHUB = "github";
  static final String GITLAB = "gitlab";
  static final String DEFAULT_GITHUB_API_BASE = "https://api.github.com";
  static final String DEFAULT_GITLAB_API_BASE = "https://gitlab.com/api/v4";

  private PlatformArgs() {}

  /**
   * 解析平台选择。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return `github` 或 `gitlab`（小写）
   */
  static String platform(Map<String, String> named, Map<String, String> env) {
    String raw = firstNonBlank(named.get("--platform"), env.get("CRA_GIT_PLATFORM"), GITHUB);
    String platform = raw.strip().toLowerCase(Locale.ROOT);
    if (!GITHUB.equals(platform) && !GITLAB.equals(platform)) {
      throw new CraConfigException("--platform / CRA_GIT_PLATFORM 非法: " + raw + "（github|gitlab）");
    }
    return platform;
  }

  /**
   * GitLab 连接设置（仅 `platform=gitlab` 时调用）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return 归一后的 GitLab 设置
   */
  static GitLabSettings gitlabSettings(Map<String, String> named, Map<String, String> env) {
    return new GitLabSettings(
        normalizeApiBase(
            firstNonBlank(
                named.get("--gitlab-base-url"),
                env.get("CRA_GITLAB_BASE_URL"),
                DEFAULT_GITLAB_API_BASE)),
        gitlabToken(env),
        blankToNull(env.get("CRA_GITLAB_BOT_USERNAME")));
  }

  /**
   * GitLab 项目路径（`group/project` 或嵌套 `group/subgroup/project`）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return 项目路径；未配置为 null
   */
  static String gitlabProject(Map<String, String> named, Map<String, String> env) {
    return blankToNull(
        firstNonBlank(
            named.get("--gitlab-project"),
            env.get("CRA_GITLAB_PROJECT"),
            env.get("CI_PROJECT_PATH")));
  }

  /**
   * 仓库标识回退（R5 共享，review/sweep 同语义）：GitLab = `--gitlab-project` &gt; `CRA_GITLAB_PROJECT` &gt;
   * `CI_PROJECT_PATH` &gt; `--repo`；GitHub = `--repo` &gt; `GITHUB_REPOSITORY`。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @param gitlab 是否 GitLab 平台
   * @return 仓库标识；未配置为 null
   */
  static String repoOrProject(Map<String, String> named, Map<String, String> env, boolean gitlab) {
    if (gitlab) {
      return blankToNull(
          firstNonBlank(
              named.get("--gitlab-project"),
              env.get("CRA_GITLAB_PROJECT"),
              env.get("CI_PROJECT_PATH"),
              named.get("--repo")));
    }
    return blankToNull(firstNonBlank(named.get("--repo"), env.get("GITHUB_REPOSITORY")));
  }

  /**
   * 仓库标识解析为 `owner/name`，缺失即拒绝（R5 共享；CI 必填形态与 sweep 用）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @param gitlab 是否 GitLab 平台
   * @return `owner/name`
   * @throws CraConfigException 仓库标识缺失（文案见 {@link #missingRepoMessage}）或格式非法
   */
  static OwnerRepo requireOwnerRepo(
      Map<String, String> named, Map<String, String> env, boolean gitlab) {
    String repo = repoOrProject(named, env, gitlab);
    if (repo == null) {
      throw new CraConfigException(missingRepoMessage(gitlab));
    }
    return splitRepo(repo, gitlab);
  }

  /** 仓库标识缺失的报错文案（review 本地形态可缺省，必填分支引用同一文案，避免双份拷贝漂移）。 */
  static String missingRepoMessage(boolean gitlab) {
    return gitlab
        ? "缺参数: --gitlab-project / CRA_GITLAB_PROJECT / CI_PROJECT_PATH（group/project）"
        : "缺参数: --repo（或 GITHUB_REPOSITORY）";
  }

  /**
   * token 解析（R5 共享，review/sweep 同语义）：GitHub = `--token` &gt; `GITHUB_TOKEN`；GitLab 拒收命令行
   * `--token`（跨平台串用密钥防护），只认 `CRA_GITLAB_TOKEN` / `CRA_GITLAB_TOKEN_FILE`。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @param gitlab 是否 GitLab 平台
   * @return token；未配置为 null（必填性由调用方按形态校验——review 本地形态可缺省）
   */
  static String tokenOrGitlabGuard(
      Map<String, String> named, Map<String, String> env, boolean gitlab) {
    if (gitlab) {
      if (named.containsKey("--token")) {
        throw new CraConfigException(
            "GitLab token 只从 CRA_GITLAB_TOKEN / CRA_GITLAB_TOKEN_FILE 读取（--token 仅 GitHub）");
      }
      return gitlabToken(env);
    }
    return blankToNull(firstNonBlank(named.get("--token"), env.get("GITHUB_TOKEN")));
  }

  /**
   * LLM env 三键（R5 共享：`CRA_LLM_API_KEY` / `CRA_LLM_BASE_URL` / `CRA_LLM_MODEL`）。required=true
   * 时逐键缺失即拒绝（密钥只从环境变量读， 05§6）；review 本地形态传 false（缺省 = 评审段如实跳过）。
   *
   * @param env 环境变量
   * @param required 是否必填（CI 形态）
   * @return 三键原始值（未配置项为 null）
   */
  static LlmEnvTriple llmEnvTriple(Map<String, String> env, boolean required) {
    String apiKey = blankToNull(env.get("CRA_LLM_API_KEY"));
    String baseUrl = blankToNull(env.get("CRA_LLM_BASE_URL"));
    String model = blankToNull(env.get("CRA_LLM_MODEL"));
    if (required) {
      if (apiKey == null) {
        throw new CraConfigException("CRA_LLM_API_KEY 未设置（密钥只从环境变量读，05§6）");
      }
      if (baseUrl == null) {
        throw new CraConfigException("CRA_LLM_BASE_URL 未设置");
      }
      if (model == null) {
        throw new CraConfigException("CRA_LLM_MODEL 未设置");
      }
    }
    return new LlmEnvTriple(apiKey, baseUrl, model);
  }

  /** LLM env 三键取值（原始值；未配置为 null）。 */
  record LlmEnvTriple(String apiKey, String baseUrl, String model) {}

  /**
   * 深度档位校验（R5 共享；quick|standard|deep）。
   *
   * @param depth 原始档位
   * @return 通过校验的档位
   */
  static String depthCheck(String depth) {
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（quick|standard|deep）");
    }
    return depth;
  }

  /**
   * CrossChecker 模式串解析（R5 共享）：`--crosschecker-modes` &gt; `CRA_CROSSCHECKER_MODES` &gt;
   * `assumption,sniff`； 逐段校验（assumption|sniff，非法/全空组装期拒绝，03§3.1）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @return 模式串原值
   */
  static String crosscheckerModes(Map<String, String> named, Map<String, String> env) {
    String modes =
        named.getOrDefault(
            "--crosschecker-modes", envOr(env, "CRA_CROSSCHECKER_MODES", "assumption,sniff"));
    boolean any = false;
    for (String part : modes.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        CrossCheckerOptions.Mode.parse(part);
      } catch (IllegalArgumentException e) {
        throw new CraConfigException("--crosschecker-modes 非法: " + modes + "（assumption|sniff）");
      }
      any = true;
    }
    if (!any) {
      throw new CraConfigException("--crosschecker-modes 不能为空（assumption|sniff）");
    }
    return modes;
  }

  /**
   * tri-state 旗标匹配（R5 共享）：{@code --flag} / {@code --flag=true} → true、{@code --flag=false} →
   * false；其余返回 null。`--flag=value` 形式仅调用方声明的旗标合法——未匹配参数落入调用方的 default 分支，仍按 `=` 截断回显旗标名（密钥掩码语义）。
   *
   * @param arg 当前参数
   * @param flag 旗标名（如 {@code --crosschecker}）
   * @return 匹配结果；非 tri-state 参数为 null
   */
  static TriState matchTriState(String arg, String flag) {
    if (flag.equals(arg) || (flag + "=true").equals(arg)) {
      return new TriState(flag, true);
    }
    if ((flag + "=false").equals(arg)) {
      return new TriState(flag, false);
    }
    return null;
  }

  /** tri-state 旗标匹配结果。 */
  record TriState(String flag, boolean value) {}

  /**
   * 拆分仓库标识：GitHub 只允许 `owner/name`；GitLab 允许嵌套 group（按最后一个 `/` 切分）。
   *
   * @param full `owner/name` 或 `group/subgroup/project`
   * @param allowNestedGroup GitLab = true
   * @return 拆分的属主与仓库名
   */
  static OwnerRepo splitRepo(String full, boolean allowNestedGroup) {
    int lastSlash = full.lastIndexOf('/');
    if (lastSlash <= 0 || lastSlash == full.length() - 1) {
      throw new CraConfigException(
          (allowNestedGroup ? "--gitlab-project 形如 group/project: " : "--repo 形如 owner/name: ")
              + full);
    }
    String owner = full.substring(0, lastSlash);
    String name = full.substring(lastSlash + 1);
    if (!allowNestedGroup && owner.contains("/")) {
      throw new CraConfigException("--repo 形如 owner/name: " + full);
    }
    return new OwnerRepo(owner, name);
  }

  /** 仓库标识（owner + name；GitLab 嵌套 group 时 owner 含 `/`）。 */
  record OwnerRepo(String owner, String name) {}

  /**
   * API 根归一：接受实例根或已含 `/api/v4` 的地址（公共体见 {@link LlmParsing#normalizeGitLabApiBase}）。
   *
   * @param raw 用户输入
   * @return 以 `/api/v4` 结尾且无尾随 `/` 的 API 根
   */
  static String normalizeApiBase(String raw) {
    return LlmParsing.normalizeGitLabApiBase(raw, DEFAULT_GITLAB_API_BASE);
  }

  /**
   * 密钥读取：`CRA_GITLAB_TOKEN` 优先，其次 `CRA_GITLAB_TOKEN_FILE` 指向的文件（文件读取公共体见 {@link
   * LlmParsing#readGitLabTokenFile}）。
   *
   * @param env 环境变量
   * @return token；均未配置为 null
   */
  static String gitlabToken(Map<String, String> env) {
    String token = blankToNull(env.get("CRA_GITLAB_TOKEN"));
    if (token != null) {
      return token;
    }
    return LlmParsing.readGitLabTokenFile(blankToNull(env.get("CRA_GITLAB_TOKEN_FILE")));
  }

  /**
   * 环境变量读取：空白值等同未设置（GitHub Actions 空 input 是 ""，不是 null），非空白值去除首尾空白。
   *
   * @param env 环境变量
   * @param key 键
   * @param defaultValue 未配置时的默认值
   * @return 归一后的值
   */
  static String envOr(Map<String, String> env, String key, String defaultValue) {
    String value = blankToNull(env.get(key));
    return value == null ? defaultValue : value;
  }

  /**
   * 首个非空白值（不去除首尾空白，调用方按需归一）。
   *
   * @param values 候选值
   * @return 首个非空白值；全空白为 null
   */
  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * 空白值归一为 null：空白 = 未设置（GitHub Actions 空 input 是 ""）；非空白值去除首尾空白。
   *
   * @param value 原始值
   * @return 去空白后的值或 null
   */
  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * 正整数解析（flag/env 共用）：null 取默认值，非正整数拒绝（退出码 2）。
   *
   * @param key 参数名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return 解析结果
   */
  static int positiveInt(String key, String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.strip());
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为正整数: " + value);
    }
  }

  /**
   * 布尔解析（R5 共享）：空/空白 = 默认值；true/false（另接受 1/0、yes/no，大小写不敏感）， 其余拒绝（fail fast，与 cra-server
   * 环境变量绑定同语义）。
   *
   * @param key 来源键名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return 布尔值
   */
  static boolean bool(String key, String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
      return true;
    }
    if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
      return false;
    }
    throw new CraConfigException(key + " 必须为布尔值（true|false）: " + value);
  }

  /** 策略值来源（flag 优先、env 次之）：value 为 null 表示未配置；key 是实际来源键名，错误文案按来源保留 flag/env 名。 */
  record Sourced(String value, String key) {}

  /**
   * review 策略取值（R5 共享：CLI 参数优先、环境变量次之；空白 env 等同未设置）。
   *
   * @param named 命令行命名参数
   * @param env 环境变量
   * @param flag 旗标名
   * @param envKey 环境变量键（05§5 review.* 策略组，review/sweep/server 共用）
   * @return 原始值 + 来源键名
   */
  static Sourced policyValue(
      Map<String, String> named, Map<String, String> env, String flag, String envKey) {
    String flagValue = named.get(flag);
    if (flagValue != null) {
      return new Sourced(flagValue, flag);
    }
    return new Sourced(blankToNull(env.get(envKey)), envKey);
  }

  /** 降级阈值对（0 &lt; first &lt; second &lt;= 1）。 */
  record DegradeAt(double first, double second) {}

  /**
   * 降级阈值解析（R5 共享）：null/空白 = 默认值；形如 {@code 0.70,0.85}。
   *
   * @param key 来源键名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return 阈值对
   */
  static DegradeAt degradeAt(String key, String value, DegradeAt defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    String[] parts = value.split(",");
    if (parts.length != 2) {
      throw new CraConfigException(key + " 形如 0.70,0.85: " + value);
    }
    try {
      double first = Double.parseDouble(parts[0].strip());
      double second = Double.parseDouble(parts[1].strip());
      if (!Double.isFinite(first)
          || !Double.isFinite(second)
          || first <= 0
          || second <= first
          || second > 1.0) {
        throw new NumberFormatException();
      }
      return new DegradeAt(first, second);
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 阈值非法（0 < first < second <= 1）: " + value);
    }
  }

  /**
   * nit 预算解析（R5 共享）：0 = 禁止 nit，是合法配置（04§3.1）；null/空白 = 默认值。
   *
   * @param key 来源键名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return 非负整数
   */
  static int nitBudget(String key, String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.strip());
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须为非负整数（0 = 禁止）: " + value);
    }
  }

  /**
   * 百分比解析（R5 共享）：0–100（0 = 关闭抽查保险丝，与 PipelineOptions/SpotCheckFuse 契约一致）。
   *
   * @param key 来源键名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return 百分比
   */
  static int percent(String key, String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.strip());
      if (parsed < 0 || parsed > 100) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new CraConfigException(key + " 必须在 [0,100]: " + value);
    }
  }

  /**
   * UNVERIFIED 发布策略解析（R5 共享）：never|labeled；null/空白 = 默认值。
   *
   * @param key 来源键名（错误文案）
   * @param value 原始值
   * @param defaultValue 缺省值
   * @return never 或 labeled
   */
  static String publishUnverified(String key, String value, String defaultValue) {
    String resolved = value == null || value.isBlank() ? defaultValue : value.strip();
    if (!"never".equals(resolved) && !"labeled".equals(resolved)) {
      throw new CraConfigException(key + " 非法: " + resolved + "（never|labeled）");
    }
    return resolved;
  }
}
