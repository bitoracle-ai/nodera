# Nodera — one image, built once, promoted unchanged.
#
# There is no environment-specific build. A build that differs per environment is a build
# that was never tested where it runs; configuration comes from the environment at start-up.

# ---- Frontend build -------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/yarn.lock ./
RUN yarn install --frozen-lockfile
COPY frontend/ ./
RUN yarn build

# ---- Backend build --------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /build
# Dependency resolution is cached on its own layer: build files change far less often than
# source, so a source-only edit does not re-download the world.
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle/ gradle/
COPY backend/*/build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY backend/ ./
RUN ./gradlew :app:installDist --no-daemon -x test

# ---- Runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Never root. A container that runs as root turns a container escape into a host compromise,
# and nothing in this image needs the privilege.
RUN addgroup -S nodera && adduser -S -G nodera nodera

WORKDIR /app
COPY --from=backend  --chown=nodera:nodera /build/app/build/install/app/ ./
COPY --from=frontend --chown=nodera:nodera /build/dist/ ./static/
COPY --chown=nodera:nodera db/migrations/ ./migrations/

USER nodera
EXPOSE 8080 8081

# The application refuses to start when a required secret is absent (invariant #6), so an
# unhealthy container here means a missing configuration value, not a transient fault.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["./bin/app"]
