package org.cra.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * cra-server 进程入口（02§5 服务形态、07§5 M4b）：第二组合根，与 cra-runner-cli 共用 cra-core。
 *
 * <p>职责边界：HTTP 适配（webhook 验签/事件解析/健康检查/报告签名）+ 有界队列与背压 + 异步执行；评审逻辑全部在 cra-core，账本在 cra-store 的
 * PostgreSQL 实现，本模块不改评审语义。
 */
@SpringBootApplication
public class ServerMain {

  /**
   * 进程入口。
   *
   * @param args Spring Boot 参数
   */
  public static void main(String[] args) {
    SpringApplication.run(ServerMain.class, args);
  }
}
