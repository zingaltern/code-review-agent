# syntax=docker/dockerfile:1.7
# cra-server 镜像（M4b）。构建上下文 = 仓库根；jar 已由 spring-boot-maven-plugin repackage。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -pl cra-server -am package -DskipTests

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home cra \
    && mkdir -p /var/lib/cra/runs /workspace \
    && chown -R cra:cra /var/lib/cra /workspace

WORKDIR /app
COPY --from=build /src/cra-server/target/cra-server-0.1.0-exec.jar /app/cra-server.jar

USER cra
EXPOSE 8080
# /readyz 检查 PG 可达（docker-compose 的 healthcheck 使用同一端点）
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/readyz || exit 1

ENTRYPOINT ["java", "-jar", "/app/cra-server.jar"]
