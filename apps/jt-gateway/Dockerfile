FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY libs/jt-protocol/pom.xml libs/jt-protocol/pom.xml
COPY apps/jt-gateway/pom.xml apps/jt-gateway/pom.xml
COPY apps/api/pom.xml apps/api/pom.xml
COPY tools/jt-terminal-simulator/pom.xml tools/jt-terminal-simulator/pom.xml

COPY libs/jt-protocol/src libs/jt-protocol/src
COPY apps/jt-gateway/src apps/jt-gateway/src
COPY tools/jt-terminal-simulator/src tools/jt-terminal-simulator/src
RUN mvn -q -pl apps/jt-gateway -am -DskipTests package

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 jt-gateway \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin jt-gateway \
    && mkdir -p /app /var/lib/jt-gateway \
    && chown -R 10001:10001 /app /var/lib/jt-gateway

WORKDIR /app
COPY --from=build --chown=10001:10001 \
    /workspace/apps/jt-gateway/target/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 7611 7612
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
