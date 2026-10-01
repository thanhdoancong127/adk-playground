# syntax=docker/dockerfile:1.7

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy every module POM first so the dependency layer is cached between builds.
COPY pom.xml .
COPY adapter/pom.xml adapter/pom.xml
COPY domain/pom.xml domain/pom.xml
COPY agents/pom.xml agents/pom.xml
COPY cli/pom.xml cli/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -N dependency:go-offline || true

# .env is never copied: .dockerignore excludes .env, .env.*, target/ and .git/.
COPY adapter/src adapter/src
COPY domain/src domain/src
COPY agents/src agents/src
COPY cli/src cli/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests && \
    cp cli/target/cli-*.jar /src/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root user with a fixed UID/GID so bind-mounted output dirs stay writable.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -h /app app \
 && mkdir -p /app/eval-out && chown -R app:app /app

COPY --from=build --chown=app:app /src/app.jar /app/app.jar

# All config comes from runtime env (compose env_file / -e). No secrets in the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER app

# exec makes java PID 1 so signals are delivered cleanly. Runnable fat jar (no -cp needed).
# Pass args, e.g. `docker compose run --rm app <prompt>`.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
