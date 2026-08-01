# syntax=docker/dockerfile:1.7
#
# One Dockerfile for all eight deployables. Each compose service selects its module
# through build args, so the expensive reactor build is a single shared stage that
# BuildKit builds once and reuses for every image.
#
# Build stage:   eclipse-temurin 25 JDK (the maven:* tag is that image with Maven
#                preinstalled; this repo has no Maven wrapper to use instead)
# Runtime stage: Amazon Corretto 25
# Both are OpenJDK builds, so mixing distributions across stages is fine.

# ---------------------------------------------------------------------------
# Stage 1 - build the whole reactor once
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
COPY . .

# The four libraries must be installed before any service module can resolve them,
# so the reactor is built as a whole rather than per module. The cache mount keeps
# ~/.m2 between builds; it is a cache, never part of the image.
# NOTE: build from api-parent/pom.xml, not the root pom. The root reactor includes
# the database module, whose liquibase:update is bound to process-resources and
# would try to reach MySQL during the image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f api-parent/pom.xml -B -DskipTests install

# ---------------------------------------------------------------------------
# Stage 2 - split one module's jar into cacheable layers
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS layers

# e.g. MODULE_PATH=services/photo-app-users-service
#      JAR_NAME=photo-app-users-service-0.0.1-SNAPSHOT.jar
ARG MODULE_PATH
ARG JAR_NAME

WORKDIR /layers
COPY --from=build /build/api-parent/${MODULE_PATH}/target/${JAR_NAME} app.jar

# Spring Boot 4 dropped the old "layertools" jarmode in favour of "tools".
RUN java -Djarmode=tools -jar app.jar extract --launcher --layers --destination extracted

# ---------------------------------------------------------------------------
# Stage 3 - runtime
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS runtime

ARG SERVICE_PORT=8080

# curl is already present in the base image as curl-minimal; installing the full curl
# package conflicts with it. Only useradd/groupadd are missing.
RUN yum install -y shadow-utils \
 && yum clean all \
 && groupadd --system photoapp \
 && useradd --system --gid photoapp --create-home --home-dir /home/photoapp photoapp \
 && mkdir -p /app /var/log/photo-app \
 && chown -R photoapp:photoapp /app /var/log/photo-app

WORKDIR /app

# Ordered least to most volatile so a code change only invalidates the last layer.
COPY --from=layers --chown=photoapp:photoapp /layers/extracted/dependencies/ ./
COPY --from=layers --chown=photoapp:photoapp /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=photoapp:photoapp /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=photoapp:photoapp /layers/extracted/application/ ./

USER photoapp

# Logback resolves ${LOG_BASE} at runtime; compose mounts the shared log volume here.
# SERVICE_PORT is promoted from ARG to ENV so HEALTHCHECK can see it at runtime.
ENV LOG_BASE=/var/log/photo-app \
    SERVICE_PORT=${SERVICE_PORT} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE ${SERVICE_PORT}

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVICE_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
