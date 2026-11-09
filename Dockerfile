# llmgw4j — v13 container packaging.
# Build:  docker build -t llmgw4j:latest .
# Run:    docker run -p 8080:8080 -e LLMGW_ADMIN_TOKEN=... -e DASHSCOPE_API_KEY=... llmgw4j:latest
# Engine: set SPRING_PROFILES_ACTIVE=webflux (default) or vt.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Cache dependencies first: only pom.xml changes invalidate this layer.
COPY pom.xml llmgw-app/pom.xml llmgw-core/pom.xml ./
COPY llmgw-governance/pom.xml llmgw-governance/
COPY llmgw-governance/llmgw-governance-api/pom.xml llmgw-governance/llmgw-governance-api/
COPY llmgw-governance/llmgw-governance-default/pom.xml llmgw-governance/llmgw-governance-default/
COPY llmgw-governance/llmgw-governance-redis/pom.xml llmgw-governance/llmgw-governance-redis/
COPY llmgw-engine/pom.xml llmgw-engine/
COPY llmgw-engine/llmgw-engine-api/pom.xml llmgw-engine/llmgw-engine-api/
COPY llmgw-engine/llmgw-engine-webflux/pom.xml llmgw-engine/llmgw-engine-webflux/
COPY llmgw-engine/llmgw-engine-vt/pom.xml llmgw-engine/llmgw-engine-vt/
COPY llmgw-provider-openai/pom.xml llmgw-provider-openai/
RUN mvn -q -B -DskipTests dependency:go-offline || true
# Then copy sources and build the fat jar (both engines are inside it).
COPY . .
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /src/llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar /app/llmgw.jar
ENV SPRING_PROFILES_ACTIVE=webflux \
    JAVA_OPTS="-XX:MaxGCPauseMillis=10"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s \
  CMD java -version > /dev/null 2>&1 && curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/llmgw.jar"]
