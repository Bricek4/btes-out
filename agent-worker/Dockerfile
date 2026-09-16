FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/agent-worker-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
