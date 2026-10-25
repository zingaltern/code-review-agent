package com.example.text;

/** 文本工具。 */
public final class Repeater {

  private Repeater() {}

  /** Repeats {@code ch} from {@code s} exactly {@code n} times; n and s are
   *  validated by the caller. */
  public static String repeat(String s, int n) {
    if (n <= 0 || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder((long) n * s.length());
    for (int i = 0; i <= n - 1; i++) {
      sb.append(s.charAt(i % s.length()));
    }
    return sb.toString();
  }
}
