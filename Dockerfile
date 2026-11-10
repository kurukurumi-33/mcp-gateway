# syntax=docker/dockerfile:1

# ----------------------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layers first: the poms change far less often than the sources, so a
# source-only edit reuses the downloaded repository instead of fetching it again.
COPY pom.xml ./
COPY mcp-core/pom.xml mcp-core/
COPY mcp-backends/pom.xml mcp-backends/
COPY mcp-spring-boot-starter/pom.xml mcp-spring-boot-starter/
COPY mcp-app/pom.xml mcp-app/
RUN mvn -B -ntp -q dependency:go-offline || true

COPY mcp-core/src mcp-core/src
COPY mcp-backends/src mcp-backends/src
COPY mcp-spring-boot-starter/src mcp-spring-boot-starter/src
COPY mcp-app/src mcp-app/src
RUN mvn -B -ntp -DskipTests package

# ----------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get -o Acquire::Retries=3 update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 gateway \
 && useradd --system --uid 10001 --gid gateway --no-create-home gateway

WORKDIR /app
COPY --from=build --chown=gateway:gateway /workspace/mcp-app/target/mcp-app-*.jar app.jar

USER gateway
EXPOSE 8931

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8931/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]