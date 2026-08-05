# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM node:22-bookworm-slim AS codex-cli
RUN --mount=type=cache,target=/root/.npm npm install --global @openai/codex@0.144.1

FROM eclipse-temurin:17-jre AS runtime
ARG APP_UID=1000
WORKDIR /app
RUN groupadd app \
    && useradd --uid "${APP_UID}" --gid app --create-home --home-dir /home/app app \
    && mkdir -p /workspace /home/app/.codex \
    && chown -R app:app /app /workspace /home/app
COPY --from=codex-cli /usr/local/bin/node /usr/local/bin/node
COPY --from=codex-cli /usr/local/lib/node_modules/@openai/codex /usr/local/lib/node_modules/@openai/codex
RUN ln -s /usr/local/lib/node_modules/@openai/codex/bin/codex.js /usr/local/bin/codex
COPY --from=build /build/target/ai-task-center-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
ENV HOME=/home/app
ENV CODEX_HOME=/home/app/.codex
EXPOSE 18744
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
