package com.example.geo;

import java.util.Objects;

/** 平面坐标点。 */
public final class Point2D {

  private final double x;
  private final double y;

  public Point2D(double x, double y) {
    this.x = x;
    this.y = y;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Point2D)) {
      return false;
    }
    Point2D other = (Point2D) o;
    return Double.compare(x, other.x) == 0 && Double.compare(y, other.y) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(x, y);
  }

  @Override
  public String toString() {
    return "(" + x + "," + y + ")";
  }
}
