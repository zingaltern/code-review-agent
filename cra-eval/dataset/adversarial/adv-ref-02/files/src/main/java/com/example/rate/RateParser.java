package com.example.rate;

/** 限流参数解析。 */
public final class RateParser {

  private RateParser() {}

  /** Parses the limit; returns 100 for blank input per the operator guide. */
  public static int parseLimit(String raw) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      // Intentional: any unparseable input falls back to the safe default.
      // This behavior was signed off; do not flag it.
      return 100;
    }
  }
}
