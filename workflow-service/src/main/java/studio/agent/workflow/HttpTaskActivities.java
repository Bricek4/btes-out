package studio.agent.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import io.temporal.failure.ApplicationFailure;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Activity adapter for Agent Worker execution. It carries only references and bounded JSON; it
 * never logs request/response bodies or stores provider credentials.
 */
public final class HttpTaskActivities implements TaskActivities {
  private static final int MAX_RESPONSE_BYTES = 1_048_576;
  private final HttpClient http;
  private final URI endpoint;
  private final String token;
  private final ObjectMapper json;
  private final PlatformStatusReporter statusReporter;

  public HttpTaskActivities(URI agentWorkerBaseUrl, String agentWorkerToken) {
    this(agentWorkerBaseUrl, agentWorkerToken, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper(),
        PlatformStatusReporter.noOp());
  }

  HttpTaskActivities(URI baseUrl, String token, HttpClient http, ObjectMapper json) {
    this(baseUrl, token, http, json, PlatformStatusReporter.noOp());
  }

  HttpTaskActivities(URI baseUrl, String token, PlatformStatusReporter statusReporter) {
    this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        new ObjectMapper(), statusReporter);
  }

  HttpTaskActivities(URI baseUrl, String token, HttpClient http, ObjectMapper json,
      PlatformStatusReporter statusReporter) {
    this.endpoint = Objects.requireNonNull(baseUrl, "agent worker URL is required");
    this.token = requireText(token, "AGENT_WORKER_TOKEN");
    this.http = Objects.requireNonNull(http, "http client is required");
    this.json = Objects.requireNonNull(json, "json mapper is required");
    this.statusReporter = Objects.requireNonNull(statusReporter, "status reporter is required");
  }

  @Override
  public ActivityOutcome execute(WorkflowInput input) {
    Objects.requireNonNull(input, "workflow input is required");
    statusReporter.report(new PlatformStatusUpdate(input.taskId(),
        studio.agent.contracts.TaskStatus.RUNNING, 0, null, null));
    String requestBody;
    try {
      requestBody = json.writeValueAsString(input);
    } catch (JacksonException failure) {
      reportFailure(input.taskId(), "AGENT_REQUEST_SERIALIZATION_FAILED");
      throw new IllegalStateException("AGENT_REQUEST_SERIALIZATION_FAILED");
    }
    URI taskEndpoint = endpoint.resolve("/internal/tasks/" + input.taskId() + "/execute");
    HttpRequest request = HttpRequest.newBuilder(taskEndpoint)
        .timeout(Duration.ofMinutes(16))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    HttpResponse<InputStream> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException failure) {
      throw new IllegalStateException("AGENT_WORKER_UNAVAILABLE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AGENT_WORKER_INTERRUPTED");
    }
    byte[] responseBody;
    try (InputStream body = response.body()) {
      long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      if (declaredLength > MAX_RESPONSE_BYTES) {
        reportFailure(input.taskId(), "AGENT_RESPONSE_TOO_LARGE");
        throw ApplicationFailure.newNonRetryableFailure("AGENT_RESPONSE_TOO_LARGE", "response exceeds limit");
      }
      responseBody = body.readNBytes(MAX_RESPONSE_BYTES + 1);
    } catch (IOException failure) {
      throw new IllegalStateException("AGENT_RESPONSE_READ_FAILED");
    }
    if (responseBody.length > MAX_RESPONSE_BYTES) {
      reportFailure(input.taskId(), "AGENT_RESPONSE_TOO_LARGE");
      throw ApplicationFailure.newNonRetryableFailure("AGENT_RESPONSE_TOO_LARGE", "response exceeds limit");
    }
    if (response.statusCode() >= 400 && response.statusCode() < 500) {
      reportFailure(input.taskId(), "AGENT_WORKER_REJECTED");
      throw ApplicationFailure.newNonRetryableFailure("AGENT_WORKER_REJECTED", "HTTP_4XX");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw ApplicationFailure.newFailure("AGENT_WORKER_REJECTED", "HTTP_5XX");
    }
    try {
      ActivityOutcome result = json.readValue(responseBody, ActivityOutcome.class);
      if (result == null) {
        reportFailure(input.taskId(), "AGENT_RESULT_INVALID");
        throw ApplicationFailure.newNonRetryableFailure("AGENT_RESULT_INVALID", "empty outcome");
      }
      reportOutcome(input.taskId(), result);
      return result;
    } catch (JacksonException failure) {
      reportFailure(input.taskId(), "AGENT_RESULT_INVALID");
      throw ApplicationFailure.newNonRetryableFailure("AGENT_RESULT_INVALID", "response is not a valid outcome");
    }
  }

  private void reportOutcome(String taskId, ActivityOutcome result) {
    Integer progress = result.status() == studio.agent.contracts.TaskStatus.SUCCEEDED ? 100 : null;
    String failure = result.status() == studio.agent.contracts.TaskStatus.CANCELED
        ? "AGENT_CANCELED" : result.failureCode();
    statusReporter.report(new PlatformStatusUpdate(taskId, result.status(), progress,
        result.artifactReference(), failure));
  }

  private void reportFailure(String taskId, String failureCode) {
    statusReporter.report(new PlatformStatusUpdate(taskId,
        studio.agent.contracts.TaskStatus.FAILED, null, null, failureCode));
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value;
  }
}
