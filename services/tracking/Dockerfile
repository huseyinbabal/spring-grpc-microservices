# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the Tracking service.
# Mirror of services/shipment/Dockerfile — see that file for detailed
# commentary on each stage.
#
# Build from the repository root:
#
#     docker build -f services/tracking/Dockerfile -t cargo-tracking:local .

# ── Stage 1 — build ──────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

ARG BUF_VERSION=1.47.2
RUN set -eux; \
    arch="$(uname -m)"; \
    curl -fsSL "https://github.com/bufbuild/buf/releases/download/v${BUF_VERSION}/buf-Linux-${arch}" \
        -o /usr/local/bin/buf; \
    chmod +x /usr/local/bin/buf; \
    buf --version

COPY pom.xml ./
COPY common-grpc/pom.xml common-grpc/pom.xml
COPY proto-gen/pom.xml proto-gen/pom.xml
COPY services/shipment/pom.xml services/shipment/pom.xml
COPY services/tracking/pom.xml services/tracking/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/tracking -am dependency:go-offline -DskipTests || true

COPY buf.yaml buf.gen.yaml ./
COPY proto proto
COPY proto-gen/buf.gen.yaml proto-gen/buf.gen.yaml
COPY common-grpc/src common-grpc/src
COPY proto-gen proto-gen
COPY services/shipment/src services/shipment/src
COPY services/tracking/src services/tracking/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/tracking -am package -DskipTests

RUN cp services/tracking/target/tracking-*-exec.jar /tmp/tracking.jar

# ── Stage 2 — jlink JRE ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS jre-build
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

# ── Stage 3 — runtime ───────────────────────────────────────────────
FROM debian:stable-slim AS runtime

ENV JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

RUN groupadd --system --gid 1001 tracking \
    && useradd --system --uid 1001 --gid tracking --no-create-home tracking

COPY --from=jre-build /opt/jre /opt/jre
COPY --from=build /tmp/tracking.jar /app/tracking.jar

USER 1001:1001
WORKDIR /app
EXPOSE 9090

ENTRYPOINT ["java","-jar","/app/tracking.jar"]
