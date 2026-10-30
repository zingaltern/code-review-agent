package org.cra.common.event;

/**
 * 事件路径与变更路径的匹配规则（ADR-0012；CrossChecker 按变更路径检索事件）。
 *
 * <p>事件源声明的路径支持两种形态：<b>文件</b>（{@code src/billing/Invoice.java}）与<b>目录前缀</b> （{@code
 * src/billing/}、{@code src/billing/**}、{@code src/billing/*}）。规范化后两者都可用 {@link #matches(String,
 * String)} 判定：文件路径精确相等；目录前缀按路径段边界匹配（{@code src/billing} 命中 {@code src/billing/Invoice.java}，但不命中
 * {@code src/billing2/Invoice.java}）。
 *
 * <p>不支持任意 glob（如 {@code **}/{@code ?} 出现在中间）——事件源应声明文件或目录前缀；遇到不支持的形态按 字面前缀处理并如实体现在采集说明里，不假装支持。
 */
public final class EventPathMatcher {

  private EventPathMatcher() {}

  /**
   * 路径规范化：反斜杠转正斜杠、去 {@code ./} 前缀、去尾部 {@code /**} / {@code /*} / {@code /}。
   *
   * @param raw 原始路径（可空）
   * @return 规范化路径；空输入返回空串
   */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String path = raw.strip().replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    // 循环剥离直到稳定：`src/billing/**/` 需先去掉尾 `/` 再去 `/**`（ADR-0012 §2.2 的目录前缀归一）
    while (true) {
      if (path.endsWith("/**")) {
        path = path.substring(0, path.length() - 3);
      } else if (path.endsWith("/*")) {
        path = path.substring(0, path.length() - 2);
      } else if (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      } else {
        break;
      }
    }
    return ".".equals(path) ? "" : path;
  }

  /**
   * 事件路径是否命中变更路径。
   *
   * @param eventPath 事件声明路径（已规范化或原始形态均可）
   * @param changedPath 变更文件路径（仓库相对）
   * @return true 若命中
   */
  public static boolean matches(String eventPath, String changedPath) {
    String event = normalize(eventPath);
    String changed = normalize(changedPath);
    if (changed.isEmpty()) {
      return false;
    }
    if (event.isEmpty() || "*".equals(event) || "**".equals(event)) {
      return true;
    }
    int wildcard = event.indexOf('*');
    if (wildcard >= 0) {
      // 仅支持尾随 glob 的常见形态；其余按字面前缀处理（ADR-0012 的诚实边界）
      String prefix = event.substring(0, wildcard);
      prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
      return !prefix.isEmpty() && (changed.equals(prefix) || changed.startsWith(prefix + "/"));
    }
    return changed.equals(event) || changed.startsWith(event + "/");
  }
}
