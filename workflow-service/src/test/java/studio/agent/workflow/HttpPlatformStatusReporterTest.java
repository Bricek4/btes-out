package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.temporal.failure.ApplicationFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;

class HttpPlatformStatusReporterTest {
  @Test
  void reportsTerminalReferenceToPlatformWithAgentToken() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    try {
      server.createContext("/internal/tasks/task-1/events", exchange -> {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      });
      server.start();
      var reporter = new HttpPlatformStatusReporter(
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "agent-token");

      reporter.report(new PlatformStatusUpdate("task-1", TaskStatus.SUCCEEDED, 100,
          "artifact://task-1", null));

      assertThat(authorization.get()).isEqualTo("Bearer agent-token");
      assertThat(body.get()).isEqualTo("{\"status\":\"SUCCEEDED\",\"progress\":100,"
          + "\"message\":null,\"resultReference\":\"artifact://task-1\","
          + "\"failureCode\":null,\"details\":\"{}\"}");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void platformRejectionIsMachineCodedWithoutResponseBodyLeakage() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/internal/tasks/task-2/events", exchange -> {
        byte[] response = "database-secret".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(403, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      var reporter = new HttpPlatformStatusReporter(
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "agent-token");

      assertThatThrownBy(() -> reporter.report(new PlatformStatusUpdate(
          "task-2", TaskStatus.FAILED, null, null, "AGENT_RESULT_INVALID")))
          .isInstanceOf(ApplicationFailure.class)
          .hasMessageContaining("PLATFORM_STATUS_REJECTED")
          .hasMessageNotContaining("database-secret");
    } finally {
      server.stop(0);
    }
  }
}
