FROM mcr.microsoft.com/playwright/java:v1.62.0-noble
WORKDIR /app
COPY target/browser-worker-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
