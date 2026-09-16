package studio.agent.workflow;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
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

  public HttpTaskActivities(URI agentWorkerBaseUrl, String agentWorkerToken) {
    this(agentWorkerBaseUrl, agentWorkerToken, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
  }

  HttpTaskActivities(URI baseUrl, String token, HttpClient http, ObjectMapper json) {
    this.endpoint = Objects.requireNonNull(baseUrl, "agent worker URL is required");
    this.token = requireText(token, "AGENT_WORKER_TOKEN");
    this.http = Objects.requireNonNull(http, "http client is required");
    this.json = Objects.requireNonNull(json, "json mapper is required");
  }

  @Override
  public ActivityOutcome execute(WorkflowInput input) {
    Objects.requireNonNull(input, "workflow input is required");
    String requestBody;
    try {
      requestBody = json.writeValueAsString(input);
    } catch (JacksonException failure) {
      throw new IllegalStateException("AGENT_REQUEST_SERIALIZATION_FAILED");
    }
    URI taskEndpoint = endpoint.resolve("/internal/tasks/" + input.taskId() + "/execute");
    HttpRequest request = HttpRequest.newBuilder(taskEndpoint)
        .timeout(Duration.ofMinutes(16))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException failure) {
      throw new IllegalStateException("AGENT_WORKER_UNAVAILABLE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AGENT_WORKER_INTERRUPTED");
    }
    if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES) {
      throw new IllegalStateException("AGENT_RESPONSE_TOO_LARGE");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("AGENT_WORKER_REJECTED");
    }
    try {
      ActivityOutcome result = json.readValue(response.body(), ActivityOutcome.class);
      if (result == null) throw new IllegalStateException("AGENT_RESULT_INVALID");
      return result;
    } catch (JacksonException failure) {
      throw new IllegalStateException("AGENT_RESULT_INVALID");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value;
  }
}
