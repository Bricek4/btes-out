FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -pl browser-worker -am -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.62.0-noble
WORKDIR /app
COPY --from=build /src/browser-worker/target/browser-worker-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
