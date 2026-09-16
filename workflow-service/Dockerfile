FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN ./mvnw -pl workflow-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/workflow-service/target/workflow-service-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
