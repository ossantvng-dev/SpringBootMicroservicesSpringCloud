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

# start-period sized from a measured cold start: `docker compose down -v` then a
# full `up`, six JVMs booting at once against an empty page cache. Spring reported
# "process running for" 171.7s (users), 170.8s (accounts), 168.6s (photos),
# 164.4s (albums), 162.7s (authorization), 110.9s (gateway); config-server and
# discovery came up in ~25s and ~19s.
#
# The old window marked a container unhealthy at start-period + retries*interval
# = 90 + 5*15 = 165s, which the five business services straddled - they flipped
# to unhealthy and then recovered on their own once they finished booting. 240s
# clears the slowest measured boot by ~40%, and the unhealthy verdict now lands
# at 240 + 75 = 315s.
#
# A longer start-period does NOT slow down a normal start: Docker still runs the
# check on the same interval during the period, and the first success marks the
# container healthy immediately. `depends_on: service_healthy` therefore gates on
# first success and is unaffected. The only cost is that a genuinely broken
# service takes longer to be reported, which is the right trade here - a
# false unhealthy on every cold start trains you to ignore the signal.
#
# One value for all eight: the infrastructure components boot far faster and
# could run tighter, but a per-service override would have to be plumbed through
# as a build arg, and HEALTHCHECK flags are not subject to Dockerfile variable
# substitution - only the CMD is.
HEALTHCHECK --interval=15s --timeout=5s --start-period=240s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVICE_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
