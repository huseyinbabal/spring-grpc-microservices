# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the Shipment service.
#
# Stage layout:
#   1. build      — full JDK + Maven + buf, runs the reactor build
#                   for services/shipment and produces the Spring Boot
#                   repackaged fat jar (`shipment-<ver>-exec.jar`).
#   2. jre-build  — `jlink` a minimal Java 21 runtime trimmed to the
#                   modules the shipment service actually needs.
#   3. runtime    — debian:stable-slim + the jlinked JRE + the fat jar.
#
# Build from the repository root (the Maven reactor + buf.yaml live
# there, not inside services/shipment):
#
#     docker build -f services/shipment/Dockerfile -t cargo-shipment:local .

# ──────────────────────────────────────────────────────────────────────
# Stage 1 — build
# ──────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

ARG BUF_VERSION=1.47.2
# proto-gen invokes `buf generate` during generate-sources, so buf
# must be on PATH inside the builder.
RUN set -eux; \
    arch="$(uname -m)"; \
    curl -fsSL "https://github.com/bufbuild/buf/releases/download/v${BUF_VERSION}/buf-Linux-${arch}" \
        -o /usr/local/bin/buf; \
    chmod +x /usr/local/bin/buf; \
    buf --version

# Prime the Maven dependency cache from just the POMs so subsequent
# source-only rebuilds don't repull every artifact.
COPY pom.xml ./
COPY common-grpc/pom.xml common-grpc/pom.xml
COPY proto-gen/pom.xml proto-gen/pom.xml
COPY services/shipment/pom.xml services/shipment/pom.xml
COPY services/tracking/pom.xml services/tracking/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/shipment -am dependency:go-offline -DskipTests || true

# Copy the rest of the reactor sources required to build shipment.
COPY buf.yaml buf.gen.yaml ./
COPY proto proto
COPY proto-gen/buf.gen.yaml proto-gen/buf.gen.yaml
COPY common-grpc/src common-grpc/src
COPY proto-gen proto-gen
COPY services/shipment/src services/shipment/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/shipment -am package -DskipTests

# Normalize the fat jar name so the runtime stage can COPY a stable path.
RUN cp services/shipment/target/shipment-*-exec.jar /tmp/shipment.jar

# ──────────────────────────────────────────────────────────────────────
# Stage 2 — jre-build (jlink minimal runtime)
# ──────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS jre-build

# Module set chosen empirically for Spring Boot 3 + Hibernate + Postgres
# JDBC + Netty/gRPC. jdk.unsupported covers sun.misc.Unsafe (Netty),
# jdk.crypto.ec covers TLS curves used by JDBC + Kafka clients.
RUN $JAVA_HOME/bin/jlink \
        --add-modules \
            java.base,java.compiler,java.desktop,java.instrument,\
java.logging,java.management,java.management.rmi,java.naming,\
java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,\
java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,\
java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,\
jdk.httpserver,jdk.jfr,jdk.management,jdk.management.agent,\
jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.unsupported \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=2 \
        --output /opt/jre

# ──────────────────────────────────────────────────────────────────────
# Stage 3 — runtime
# ──────────────────────────────────────────────────────────────────────
FROM debian:stable-slim AS runtime

ENV JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

# Non-root user for the app.
RUN groupadd --system --gid 1001 shipment \
    && useradd --system --uid 1001 --gid shipment --no-create-home shipment

COPY --from=jre-build /opt/jre /opt/jre
COPY --from=build /tmp/shipment.jar /app/shipment.jar

USER 1001:1001
WORKDIR /app
EXPOSE 9090

ENTRYPOINT ["java","-jar","/app/shipment.jar"]
