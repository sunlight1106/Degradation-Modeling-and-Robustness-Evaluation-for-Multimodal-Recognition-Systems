FROM maven:3.9.9-eclipse-temurin-17 AS build
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml .
ARG MVN_OPTS=-B -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=120
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn $MVN_OPTS -DskipTests dependency:go-offline
COPY src/main ./src/main
COPY src/test ./src/test
COPY ai/src/main/java ./ai/src/main/java
COPY database/migrations ./database/migrations
COPY database/vocabulary ./database/vocabulary
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn $MVN_OPTS package
# Use the ordinary JVM classpath instead of repeatedly loading nested JARs.
RUN java -Djarmode=tools -jar target/*.jar extract --destination /workspace/application --application-filename app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 appuser \
    && mkdir -p /app/data/uploads /app/data/media-tmp \
    && chown -R appuser:appuser /app
COPY --from=build /workspace/application/ /app/
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
