package org.cra.llm.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.cra.common.exception.CraConfigException;

/**
 * 组合根共享解析家（R4）：CLI（cra-runner-cli）与 server（cra-server）两处组合根逐字重复的"同语义"解析在此收敛。
 *
 * <p>设计约束：全部无状态静态函数——值由调用方传入、环境变量名由调用方传入（组合根各自持有 key 名），错误文案以<b>前缀参数</b>保留各自的 key 名 （如 {@code --rpm
 * 条目形如} vs {@code CRA_LLM_RPM 条目形如}），文案模板两侧统一。落位 cra-llm 是因为它是本收敛范围内两组合根唯一的共同依赖模块； opencode
 * 适配与三档装配也属 LLM 形态，GitLab 连接与 agents.budget 键构建属组合根间漂移最多的拷贝，一并归口（05§5/05§6）。
 */
public final class LlmParsing {

  private LlmParsing() {}

  /**
   * API 根归一：接受实例根或已含 {@code /api/v4} 的地址（PlatformArgs.normalizeApiBase ≈
   * ServerConfig.normalizeGitLabApiBase 的公共体）。
   *
   * @param raw 用户输入（null/空白取 fallback）
   * @param fallback raw 为空时的默认根
   * @return 以 {@code /api/v4} 结尾且无尾随 {@code /} 的 API 根
   */
  public static String normalizeGitLabApiBase(String raw, String fallback) {
    String value = raw == null || raw.isBlank() ? fallback : raw.strip();
    String base = value.replaceAll("/+$", "");
    if (base.endsWith("/api/v4")) {
      return base;
    }
    int marker = base.indexOf("/api/v4/");
    if (marker >= 0) {
      return base.substring(0, marker + "/api/v4".length());
    }
    return base + "/api/v4";
  }

  /**
   * 降级链解析（05§5 fallback-chain；逗号分隔档位名，空 = 无兜底）。
   *
   * @param value 原始值（可为 null/空白）
   * @param errorPrefix 错误文案前缀（调用方 key 名，如 {@code fallback-chain} / {@code CRA_LLM_FALLBACK_CHAIN}）
   * @return 档位列表
   */
  public static List<ModelTier> parseFallbackChain(String value, String errorPrefix) {
    List<ModelTier> chain = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return chain;
    }
    for (String part : value.split(",")) {
      String name = part.strip();
      if (name.isEmpty()) {
        continue;
      }
      try {
        chain.add(ModelTier.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new CraConfigException(errorPrefix + " 档位非法: " + name + "（standard|strong|light）");
      }
    }
    return chain;
  }

  /**
   * per-model RPM 解析（05§5 per-model-rpm；形如 {@code standard=60,strong=30,light=60}，空 = 默认值）。空表（如
   * {@code ","}）等同未配置——返回默认表。
   *
   * @param value 原始值（可为 null/空白）
   * @param errorPrefix 错误文案前缀（调用方 key 名，如 {@code --rpm} / {@code CRA_LLM_RPM}）
   * @param defaults 空/未配置时的默认 RPM 表
   * @return 档位 → RPM（不可变）
   */
  public static Map<ModelTier, Integer> parseRpm(
      String value, String errorPrefix, Map<ModelTier, Integer> defaults) {
    if (value == null || value.isBlank()) {
      return defaults;
    }
    Map<ModelTier, Integer> rpm = new EnumMap<>(ModelTier.class);
    for (String part : value.split(",")) {
      String entry = part.strip();
      if (entry.isEmpty()) {
        continue;
      }
      int eq = entry.indexOf('=');
      if (eq <= 0 || eq == entry.length() - 1) {
        throw new CraConfigException(errorPrefix + " 条目形如 standard=60: " + entry);
      }
      String tierName = entry.substring(0, eq).strip();
      String number = entry.substring(eq + 1).strip();
      try {
        ModelTier tier = ModelTier.valueOf(tierName.toUpperCase(Locale.ROOT));
        int parsed = Integer.parseInt(number);
        if (parsed <= 0) {
          throw new NumberFormatException();
        }
        rpm.put(tier, parsed);
      } catch (IllegalArgumentException e) {
        throw new CraConfigException(
            errorPrefix + " 条目非法（档位 standard|strong|light，正整数 RPM）: " + entry);
      }
    }
    return rpm.isEmpty() ? defaults : Map.copyOf(rpm);
  }

  /**
   * 采样温度解析（05§5 CRA_LLM_TEMPERATURE；空/未配置 = null 不传，沿用 provider 默认）。
   *
   * @param value 原始值（可为 null/空白）
   * @param errorPrefix 错误文案前缀（调用方 key 名）
   * @return 0.0–2.0 的 Double；null 表示不传
   */
  public static Double parseTemperature(String value, String errorPrefix) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      double t = Double.parseDouble(value.strip());
      if (t < 0 || t > 2) {
        throw new NumberFormatException();
      }
      return t;
    } catch (NumberFormatException e) {
      throw new CraConfigException(errorPrefix + " 非法（0.0–2.0）: " + value);
    }
  }

  /**
   * 逗号分隔集合解析 + 组装期校验（空段忽略；至少一个非空段；非法段即拒）。CrossChecker modes 的 CLI/server 公共体——逐段解析器与错误文案由调用方注入，
   * 保留两侧既有 key 名与文案差异。
   *
   * @param raw 原始值（逗号分隔）
   * @param tokenParser 单段解析器（抛 IllegalArgumentException 表示非法）
   * @param invalidError 非法段异常工厂（入参 = 原始串与底层异常）
   * @param emptyMessage 全空时的异常文案
   * @param <T> 段目标类型
   * @return 去重后的段集合
   */
  public static <T> Set<T> parseCsvSet(
      String raw,
      Function<String, T> tokenParser,
      BiFunction<String, IllegalArgumentException, CraConfigException> invalidError,
      String emptyMessage) {
    Set<T> out = new LinkedHashSet<>();
    if (raw == null) {
      // null 视同未配置：与空串同走 emptyMessage（组装期 CraConfigException），不得 NPE
      throw new CraConfigException(emptyMessage);
    }
    for (String part : raw.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      try {
        out.add(tokenParser.apply(part));
      } catch (IllegalArgumentException e) {
        throw invalidError.apply(raw, e);
      }
    }
    if (out.isEmpty()) {
      throw new CraConfigException(emptyMessage);
    }
    return Set.copyOf(out);
  }

  /**
   * 单代理预算覆盖取值表（05§5 {@code agents.budget.<role>.*} 的键构建公共体）：config 键 {@code
   * agents.budget.<role>.<dim>} ← env 键 {@code CRA_AGENTS_BUDGET_<ROLE>_<DIM>}（role 内 {@code -} 归一
   * {@code _}）。取值函数由调用方注入（CLI = flag &gt; env；server = Spring Environment）。
   *
   * @param roles 可配置角色（AgentBudgetOverrides.CONFIGURABLE_ROLES）
   * @param resolveValue 取值函数（入参 = flag 名与 env 键名；返回原始值或 null）
   * @return config 键 → 原始值（保序）
   */
  public static Map<String, String> agentBudgetValues(
      Iterable<String> roles, BiFunction<String, String, String> resolveValue) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String role : roles) {
      String envPrefix = "CRA_AGENTS_BUDGET_" + role.toUpperCase(Locale.ROOT).replace('-', '_');
      values.put(
          "agents.budget." + role + ".input-tokens",
          resolveValue.apply("--" + role + "-input-tokens", envPrefix + "_INPUT_TOKENS"));
      values.put(
          "agents.budget." + role + ".output-tokens",
          resolveValue.apply("--" + role + "-output-tokens", envPrefix + "_OUTPUT_TOKENS"));
    }
    return values;
  }

  /**
   * GitLab token 密钥文件读取（05§6：容器/K8s secret mount 形态）：只返回去空白内容，失败信息不含内容。
   *
   * @param path 文件路径（null/空白 = 未配置，返回 null）
   * @return token 或 null
   */
  public static String readGitLabTokenFile(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      String value = Files.readString(Path.of(path.strip()), StandardCharsets.UTF_8);
      return value.isBlank() ? null : value.strip();
    } catch (IOException | InvalidPathException e) {
      throw new CraConfigException("CRA_GITLAB_TOKEN_FILE 读取失败: " + path);
    }
  }

  /**
   * 附加请求头（v1.3.2 opencode-go 适配）：base-url 指向 opencode 时自动附加 {@code x-opencode-session}（opencode-go
   * 的路由要求——缺失时返回 MissingSessionID）。 每个网关实例一个 UUID 会话（同一 run 的请求共享会话，符合其"会话"语义）。
   *
   * @param baseUrl LLM 端点根地址
   * @return 附加头（非 opencode 端点为空 map）
   */
  public static Map<String, String> opencodeExtraHeaders(String baseUrl) {
    if (baseUrl != null && baseUrl.contains("opencode.ai")) {
      return Map.of("x-opencode-session", UUID.randomUUID().toString());
    }
    return Map.of();
  }

  /**
   * 推理力度（v1.3.2 opencode 适配）：opencode 端点自动 none——reasoning token 计入 max_tokens，评审任务的 1–2k
   * 输出预算会被推理吃光（M3 冒烟实测 budget_exceeded）。
   *
   * @param baseUrl LLM 端点根地址
   * @return "none" 或 null（不传）
   */
  public static String opencodeReasoningEffort(String baseUrl) {
    return baseUrl != null && baseUrl.contains("opencode.ai") ? "none" : null;
  }

  /**
   * 三档路由配置装配（T2.9 形态；05§5）：standard 端点为基座，strong/light 缺省继承 standard（同 base-url
   * 同密钥，仅模型名可异），超时/并发/重试取 02§4.5 组装默认，opencode 适配（附加头 + reasoning=none）按 base-url 自动施加。
   *
   * @param baseUrl LLM 端点根地址（standard 档 base-url）
   * @param apiKey LLM 密钥
   * @param standardModel standard 档模型名（须非空）
   * @param strongModel strong 档模型名（null/空白 = 继承 standard）
   * @param lightModel light 档模型名（null/空白 = 继承 standard）
   * @param fallbackChain 降级链（档位须已配置端点）
   * @param rpm per-model RPM 表
   * @param temperature 采样温度（null = 不传）
   * @return 网关配置
   */
  public static LlmGatewayConfig threeTierConfig(
      String baseUrl,
      String apiKey,
      String standardModel,
      String strongModel,
      String lightModel,
      List<ModelTier> fallbackChain,
      Map<ModelTier, Integer> rpm,
      Double temperature) {
    LlmGatewayConfig.ModelEndpoint standard =
        new LlmGatewayConfig.ModelEndpoint(standardModel, baseUrl, apiKey);
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(
            standard,
            new LlmGatewayConfig.ModelEndpoint(
                modelOrInherit(strongModel, standardModel), baseUrl, apiKey),
            new LlmGatewayConfig.ModelEndpoint(
                modelOrInherit(lightModel, standardModel), baseUrl, apiKey)),
        fallbackChain,
        rpm,
        LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY,
        LlmGatewayConfig.DEFAULT_MAX_RETRIES,
        30_000,
        120_000,
        1_000,
        8_000,
        temperature,
        opencodeExtraHeaders(baseUrl),
        opencodeReasoningEffort(baseUrl));
  }

  /** 档位模型名缺省继承 standard；空白串等同未配置（ModelEndpoint 拒绝 blank）。 */
  private static String modelOrInherit(String model, String standard) {
    return model == null || model.isBlank() ? standard : model;
  }
}
