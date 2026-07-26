FROM maven:3.8.7-eclipse-temurin-8 AS build

WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:8-jre

WORKDIR /app
RUN mkdir -p /app/public/video /app/public/chunk /app/public/img/cover \
    && chown -R 10001:0 /app \
    && chmod -R g=u /app

COPY --from=build --chown=10001:0 \
    /workspace/target/waitfans-backend-0.0.1-SNAPSHOT.jar \
    /app/waitfans-backend.jar

USER 10001
EXPOSE 7070 7071

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/waitfans-backend.jar"]
