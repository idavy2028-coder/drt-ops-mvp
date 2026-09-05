FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY libs/jt-protocol/pom.xml libs/jt-protocol/pom.xml
COPY apps/jt-gateway/pom.xml apps/jt-gateway/pom.xml
COPY apps/api/pom.xml apps/api/pom.xml
COPY tools/jt-terminal-simulator/pom.xml tools/jt-terminal-simulator/pom.xml

COPY libs/jt-protocol/src libs/jt-protocol/src
COPY apps/jt-gateway/src apps/jt-gateway/src
COPY apps/api/src apps/api/src
COPY tools/jt-terminal-simulator/src tools/jt-terminal-simulator/src
RUN mvn -q -pl apps/api -am -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
