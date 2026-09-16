package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskType;
import tools.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;

class HttpTaskActivitiesTest {
  @Test
  void sendsOpaqueReferencesWithAgentTokenAndParsesBoundedOutcome() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      var seen = new StringBuilder();
      server.createContext("/internal/tasks/task-1/execute", exchange -> {
        seen.append(exchange.getRequestMethod()).append('|')
            .append(exchange.getRequestHeaders().getFirst("Authorization")).append('|')
            .append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"status\":\"SUCCEEDED\",\"artifactReference\":\"artifact://task-1\",\"failureCode\":null}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      var activities = new HttpTaskActivities(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          "agent-token", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper());
      var outcome = activities.execute(new WorkflowInput("task-1", "project-1", TaskType.PROJECT_DOCS,
          "source://1", "template://1", "parameters://1", "provider://1", false));
      assertThat(outcome.status()).isEqualTo(studio.agent.contracts.TaskStatus.SUCCEEDED);
      assertThat(seen.toString()).contains("POST|Bearer agent-token|", "sourceReference").doesNotContain("password");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mapsRejectedAgentResponsesToStableErrorCodeWithoutResponseBody() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/internal/tasks/task-2/execute", exchange -> {
        byte[] response = "provider-secret-body".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(502, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      var activities = new HttpTaskActivities(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          "agent-token", HttpClient.newHttpClient(), new ObjectMapper());
      assertThatThrownBy(() -> activities.execute(new WorkflowInput("task-2", "project-2", TaskType.HTML,
          "source://2", "template://2", "parameters://2", "provider://2", false)))
          .isInstanceOf(ApplicationFailure.class).hasMessageContaining("AGENT_WORKER_REJECTED")
          .hasMessageNotContaining("provider-secret-body");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void boundsStreamingAgentResponsesBeforeTheyCanFillTheHeap() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/internal/tasks/task-3/execute", exchange -> {
        byte[] response = new byte[1_048_577];
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      var activities = new HttpTaskActivities(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          "agent-token", HttpClient.newHttpClient(), new ObjectMapper());
      assertThatThrownBy(() -> activities.execute(new WorkflowInput("task-3", "project-3", TaskType.HTML,
          "source://3", "template://3", "parameters://3", "provider://3", false)))
          .isInstanceOf(ApplicationFailure.class).hasMessageContaining("AGENT_RESPONSE_TOO_LARGE");
    } finally {
      server.stop(0);
    }
  }
}
