# Nodera — one image, three entrypoints, built once and promoted unchanged (ADR-0006).
#
# There is no environment-specific build. A build that differs per environment is a build that was
# never tested where it runs; configuration comes from the environment at start-up.

# ---- Frontend build -------------------------------------------------------
# Pinned to the minor, not the major: frontend/package.json declares engines.node >=22.22.0 and
# yarn 1 aborts rather than warns, so a cached node:22-alpine older than that fails the build.
FROM node:22.23-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/yarn.lock ./
RUN yarn install --frozen-lockfile
COPY frontend/ ./
RUN yarn build

# ---- Backend build --------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS backend
ARG VERSION=unknown
WORKDIR /src/backend

# The migrations are packaged onto the classpath by :persistence, from one level above the Gradle
# root. They come first because they change less often than either build files or source.
COPY db/migrations/ /src/db/migrations/

# Dependency resolution on its own layer: build files change far less often than source, so a
# source-only edit does not re-download the world.
#
# Each module's build file keeps its own path. Copying them with a wildcard into one directory
# collapses six same-named files into one and overwrites the root build script with the last of
# them — which is what this file did before, silently, because the warm-up that followed was
# suffixed with a `|| true` that swallowed the failure.
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle/ gradle/
COPY backend/domain/build.gradle.kts       domain/
COPY backend/application/build.gradle.kts  application/
COPY backend/persistence/build.gradle.kts  persistence/
COPY backend/api-rest/build.gradle.kts     api-rest/
COPY backend/api-mcp/build.gradle.kts      api-mcp/
COPY backend/app/build.gradle.kts          app/
RUN chmod +x gradlew && ./gradlew classes --no-daemon

COPY backend/ ./
# Tests ran in CI against a real Postgres; running them here would need a database this stage does
# not have, and a test suite that silently skips is worse than one that does not run.
RUN ./gradlew :app:installDist --no-daemon -x test -Pversion="$VERSION"

# ---- Runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Never root. A container that runs as root turns a container escape into a host compromise,
# and nothing in this image needs the privilege.
RUN addgroup -S nodera && adduser -S -G nodera nodera

WORKDIR /app
COPY --from=backend  --chown=nodera:nodera /src/backend/app/build/install/app/ ./
COPY --from=frontend --chown=nodera:nodera /build/dist/ ./static/

USER nodera
EXPOSE 8080 8081

# Readiness, not liveness: this is the probe that decides whether the container should receive
# traffic, and it reports not-ready while migrations are outstanding. The application refuses to
# start when a required secret is absent (invariant #6), so an unhealthy container here means a
# missing configuration value or an unapplied migration, not a transient fault.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- "http://localhost:${NODERA_HTTP_PORT:-8080}/health/ready" || exit 1

# The Gradle start script ends in `exec "$JAVACMD" "$@"`, so the JVM is PID 1 and receives SIGTERM
# directly — which is what makes the graceful shutdown in Serve.kt reachable at all.
ENTRYPOINT ["./bin/app"]
CMD ["serve"]
