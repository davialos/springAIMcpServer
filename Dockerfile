# ──────────────────────────────────────────────────────────────────────────────
# springAIMcpServerCommon — multi-stage Dockerfile
#
# Stage 1 (builder):  installs the library modules into the local Maven cache,
#                     then builds the demo host application into a fat JAR.
# Stage 2 (runtime):  copies only the fat JAR onto a minimal JRE image.
#
# Baseline: Java 25, Spring Boot 4.1.1
# ──────────────────────────────────────────────────────────────────────────────

# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace

# --- dependency cache layer ---
# Copy only POM files first so Docker can cache the Maven dependency download
# layer independently from source changes.
COPY pom.xml ./
COPY spring-ai-mcp-server-common-bom/pom.xml                spring-ai-mcp-server-common-bom/
COPY spring-ai-mcp-server-common-annotations/pom.xml        spring-ai-mcp-server-common-annotations/
COPY spring-ai-mcp-server-common-core/pom.xml               spring-ai-mcp-server-common-core/
COPY spring-ai-mcp-server-common-persistence/pom.xml        spring-ai-mcp-server-common-persistence/
COPY spring-ai-mcp-server-common-security/pom.xml           spring-ai-mcp-server-common-security/
COPY spring-ai-mcp-server-common-query/pom.xml              spring-ai-mcp-server-common-query/
COPY spring-ai-mcp-server-common-ai/pom.xml                 spring-ai-mcp-server-common-ai/
COPY spring-ai-mcp-server-common-mcp/pom.xml                spring-ai-mcp-server-common-mcp/
COPY spring-ai-mcp-server-common-webmvc/pom.xml             spring-ai-mcp-server-common-webmvc/
COPY spring-ai-mcp-server-common-autoconfigure/pom.xml      spring-ai-mcp-server-common-autoconfigure/
COPY spring-ai-mcp-server-common-spring-boot-starter/pom.xml spring-ai-mcp-server-common-spring-boot-starter/
COPY docker/demo-app/pom.xml                                docker/demo-app/

# Download all library dependencies (cached unless a POM changes)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -q --no-transfer-progress

# --- full source ---
COPY spring-ai-mcp-server-common-bom/                       spring-ai-mcp-server-common-bom/
COPY spring-ai-mcp-server-common-annotations/               spring-ai-mcp-server-common-annotations/
COPY spring-ai-mcp-server-common-core/                      spring-ai-mcp-server-common-core/
COPY spring-ai-mcp-server-common-persistence/               spring-ai-mcp-server-common-persistence/
COPY spring-ai-mcp-server-common-security/                  spring-ai-mcp-server-common-security/
COPY spring-ai-mcp-server-common-query/                     spring-ai-mcp-server-common-query/
COPY spring-ai-mcp-server-common-ai/                        spring-ai-mcp-server-common-ai/
COPY spring-ai-mcp-server-common-mcp/                       spring-ai-mcp-server-common-mcp/
COPY spring-ai-mcp-server-common-webmvc/                    spring-ai-mcp-server-common-webmvc/
COPY spring-ai-mcp-server-common-autoconfigure/             spring-ai-mcp-server-common-autoconfigure/
COPY spring-ai-mcp-server-common-spring-boot-starter/       spring-ai-mcp-server-common-spring-boot-starter/
COPY docker/demo-app/                                       docker/demo-app/

# 1. Install the library modules into the local cache (skip tests — tests need
#    Testcontainers / Docker-in-Docker and are run separately in CI).
# 2. Build the demo host app as a fat/executable JAR.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml install -DskipTests -q --no-transfer-progress && \
    mvn -f docker/demo-app/pom.xml package -DskipTests -q --no-transfer-progress

# Unpack the fat JAR into the layered format Spring Boot produces so the
# runtime stage can copy the layers separately (better cache hit on re-deploy).
RUN java -Djarmode=tools \
         -jar docker/demo-app/target/spring-ai-mcp-demo-app-*.jar \
         extract --layers --launcher --destination /workspace/extracted

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble AS runtime

# Non-root user for the JVM process
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup --home /app --shell /bin/false appuser

WORKDIR /app

# Copy Spring Boot layered JAR — ordered from least to most likely to change
# so Docker layer caching is maximally effective across re-deploys.
COPY --chown=appuser:appgroup --from=builder /workspace/extracted/dependencies/          ./
COPY --chown=appuser:appgroup --from=builder /workspace/extracted/spring-boot-loader/    ./
COPY --chown=appuser:appgroup --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --chown=appuser:appgroup --from=builder /workspace/extracted/application/           ./

USER appuser

# Tune JVM for container-aware memory usage
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8"

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
