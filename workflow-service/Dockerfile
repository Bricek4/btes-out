FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/workflow-service-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
