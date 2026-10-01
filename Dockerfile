# syntax=docker/dockerfile:1.7

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy every module POM first so the dependency layer is cached between builds.
COPY pom.xml .
COPY adk-openai-adapter/pom.xml adk-openai-adapter/pom.xml
COPY playground-domain/pom.xml playground-domain/pom.xml
COPY playground-agents/pom.xml playground-agents/pom.xml
COPY playground-cli/pom.xml playground-cli/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -N dependency:go-offline || true

# .env is never copied: .dockerignore excludes .env, .env.*, target/ and .git/.
COPY adk-openai-adapter/src adk-openai-adapter/src
COPY playground-domain/src playground-domain/src
COPY playground-agents/src playground-agents/src
COPY playground-cli/src playground-cli/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests && \
    cp playground-cli/target/playground-cli-*.jar /src/app.jar

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
