# syntax=docker/dockerfile:1.7
ARG DEPENDENCY_BASE_IMAGE
FROM ${DEPENDENCY_BASE_IMAGE}

WORKDIR /app
COPY snapshot-dependencies/ ./
COPY application/ ./
