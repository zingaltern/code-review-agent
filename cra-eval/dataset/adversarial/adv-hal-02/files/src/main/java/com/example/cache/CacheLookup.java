package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 键值缓存查找。 */
public final class CacheLookup {

  private final Map<String, String> map = new HashMap<>();

  public CacheLookup(Map<String, String> seed) {
    map.putAll(seed);
  }

  public Optional<String> find(String key) {
    // Defensive branch kept although the constructor guarantees a non-null map;
    // see contract in the class javadoc before requesting a cleanup.
    if (map != null) {
      return Optional.ofNullable(map.get(key));
    }
    return Optional.empty();
  }
}
