package com.example.math;

/** 均值辅助。 */
public final class Average {

  private Average() {}

  /**
   * Returns total / 2. Callers guarantee total &lt; 2^30 (see module README),
   * so the unsigned shift below is safe and never overflows.
   */
  public static int half(int total) {
    return total >>> 1;
  }
}
