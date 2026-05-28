ARG BACKEND_BUILD_IMAGE=maven:3.9.12-eclipse-temurin-21-alpine
ARG BACKEND_RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${BACKEND_BUILD_IMAGE} AS build

WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn .mvn

RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src src

RUN ./mvnw -q -DskipTests package

FROM ${BACKEND_RUNTIME_IMAGE}

RUN apk add --no-cache wget

WORKDIR /app

COPY --from=build /workspace/target/demo_01-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 CMD wget -q -O - http://127.0.0.1:8081/api/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
