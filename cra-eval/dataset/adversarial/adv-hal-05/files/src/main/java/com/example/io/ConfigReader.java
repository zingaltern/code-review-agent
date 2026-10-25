package com.example.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 配置读取。 */
public final class ConfigReader {

  private ConfigReader() {}

  public static Properties load(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      Properties props = new Properties();
      props.load(in);
      return props;
    }
  }
}
