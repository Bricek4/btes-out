package studio.agent.workflow;

import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Reports machine-only lifecycle events to Platform without reading or logging response bodies. */
final class HttpPlatformStatusReporter implements PlatformStatusReporter {
  private final URI platformBaseUrl;
  private final String token;
  private final HttpClient http;
  private final ObjectMapper json;

  HttpPlatformStatusReporter(URI platformBaseUrl, String token) {
    this(platformBaseUrl, token,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
  }

  HttpPlatformStatusReporter(URI platformBaseUrl, String token, HttpClient http, ObjectMapper json) {
    this.platformBaseUrl = Objects.requireNonNull(platformBaseUrl, "platform URL is required");
    if (!"http".equalsIgnoreCase(platformBaseUrl.getScheme())
        && !"https".equalsIgnoreCase(platformBaseUrl.getScheme())) {
      throw new IllegalArgumentException("platform URL must use HTTP(S)");
    }
    if (token == null || token.isBlank()) throw new IllegalArgumentException("agent token is required");
    this.token = token;
    this.http = Objects.requireNonNull(http, "http client is required");
    this.json = Objects.requireNonNull(json, "json mapper is required");
  }

  @Override
  public void report(PlatformStatusUpdate update) {
    Objects.requireNonNull(update, "status update is required");
    byte[] body;
    try {
      body = json.writeValueAsBytes(new PlatformEventBody(update.status().name(), update.progress(),
          null, update.resultReference(), update.failureCode(), "{}"));
    } catch (JacksonException failure) {
      throw ApplicationFailure.newNonRetryableFailure(
          "PLATFORM_STATUS_SERIALIZATION_FAILED", "STATUS_SERIALIZATION");
    }
    HttpRequest request = HttpRequest.newBuilder(platformBaseUrl.resolve(
            "/internal/tasks/" + update.taskId() + "/events"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<Void> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException failure) {
      throw ApplicationFailure.newFailure("PLATFORM_STATUS_UNAVAILABLE", "STATUS_NETWORK");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw ApplicationFailure.newFailure("PLATFORM_STATUS_INTERRUPTED", "STATUS_INTERRUPTED");
    }
    if (response.statusCode() >= 400 && response.statusCode() < 500) {
      throw ApplicationFailure.newNonRetryableFailure("PLATFORM_STATUS_REJECTED", "HTTP_4XX");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw ApplicationFailure.newFailure("PLATFORM_STATUS_REJECTED", "HTTP_5XX");
    }
  }

  private record PlatformEventBody(String status, Integer progress, String message,
                                   String resultReference, String failureCode, String details) { }
}
