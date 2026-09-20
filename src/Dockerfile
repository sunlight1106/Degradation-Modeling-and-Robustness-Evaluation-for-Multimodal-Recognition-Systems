FROM maven:3.9.9-eclipse-temurin-17 AS build
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
ARG MVN_OPTS=-B -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=120
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn $MVN_OPTS -DskipTests dependency:go-offline \
    || mvn $MVN_OPTS -DskipTests dependency:go-offline \
    || echo "dependency:go-offline incomplete; package step will fetch the rest"
COPY src ./src
COPY ai ./ai
COPY database ./database
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn $MVN_OPTS package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 appuser \
    && mkdir -p /app/data/uploads /app/data/media-tmp \
    && chown -R appuser:appuser /app
COPY --from=build /workspace/target/*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
