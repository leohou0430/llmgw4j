# Build: docker build -t llmgw4j:latest .
# Redis: docker build --build-arg MAVEN_PROFILES=-Predis -t llmgw4j:redis .
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
ARG MAVEN_PROFILES=""
COPY . .
RUN mvn -q -B $MAVEN_PROFILES verify

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* && mkdir -p /app/data
COPY --from=build /src/llmgw-app/target/llmgw-app-0.3.0.jar /app/llmgw.jar
ENV SPRING_PROFILES_ACTIVE=webflux \
    JAVA_OPTS="-XX:MaxGCPauseMillis=10"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/llmgw.jar"]
