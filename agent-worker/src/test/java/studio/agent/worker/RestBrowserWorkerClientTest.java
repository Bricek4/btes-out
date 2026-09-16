package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class RestBrowserWorkerClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() { };

  @Test void sendsTaskScopedTypedLocatorPayloadsWithTheAgentToken() throws Exception {
    UUID taskId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    List<CapturedRequest> requests = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/sessions", exchange -> handleBrowser(exchange, sessionId, requests));
    server.start();
    try {
      var client = new RestBrowserWorkerClient("http://127.0.0.1:" + server.getAddress().getPort(), "agent-secret");
      String session = client.open(taskId, "https://app.example", "admin");
      client.login(session);
      client.fill(session, new SemanticLocator("label", null, "Email"), "ada@example.test");
      client.select(session, new SemanticLocator("label", null, "Status"), "Active");
      client.check(session, new SemanticLocator("role", "checkbox", "Enabled"));
      client.uncheck(session, new SemanticLocator("test-id", null, "send-invite"));
      client.waitFor(session, new SemanticLocator("role", "heading", "Users"));
      assertEquals("artifact://" + taskId + "/users/users.png", client.screenshot(session, "users", "User list"));
      client.close(session);

      CapturedRequest fill = requests.stream().filter(r -> r.path().endsWith("/fill")).findFirst().orElseThrow();
      assertEquals("Bearer agent-secret", fill.authorization());
      assertEquals(taskId.toString(), fill.body().get("taskId"));
      assertEquals("ada@example.test", fill.body().get("value"));
      assertEquals(Map.of("kind", "label", "name", "Email"), fill.body().get("locator"));
      assertFalse(((Map<?, ?>) fill.body().get("locator")).containsKey("value"));

      CapturedRequest screenshot = requests.stream().filter(r -> r.path().endsWith("/screenshot")).findFirst().orElseThrow();
      assertEquals("User list", screenshot.body().get("caption"));
      assertTrue(requests.stream().allMatch(r -> "Bearer agent-secret".equals(r.authorization())));
    } finally {
      server.stop(0);
    }
  }

  @Test void returnsOnlyTheTypedBrowserFailureCode() throws Exception {
    UUID sessionId = UUID.randomUUID();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/sessions", exchange -> {
      byte[] input = exchange.getRequestBody().readAllBytes();
      Map<String, Object> body = input.length == 0 ? Map.of() : JSON.readValue(input, STRING_OBJECT_MAP);
      String response = exchange.getRequestURI().getPath().endsWith("/login")
          ? "{\"status\":\"FAILED\",\"error\":{\"code\":\"LOGIN_FAILED\",\"message\":\"password=must-not-escape\"}}"
          : "{\"sessionId\":\"" + sessionId + "\",\"taskId\":\"" + body.get("taskId") + "\",\"profileReference\":\"admin\",\"status\":\"SUCCEEDED\"}";
      byte[] output = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, output.length);
      exchange.getResponseBody().write(output);
      exchange.close();
    });
    server.start();
    try {
      var client = new RestBrowserWorkerClient("http://127.0.0.1:" + server.getAddress().getPort(), "agent-secret");
      String session = client.open(UUID.randomUUID(), "https://app.example", "admin");
      RuntimeException failure = assertThrows(RuntimeException.class, () -> client.login(session));
      assertEquals("LOGIN_FAILED", failure.getMessage());
      assertFalse(failure.toString().contains("must-not-escape"));
    } finally {
      server.stop(0);
    }
  }

  private static void handleBrowser(HttpExchange exchange, UUID sessionId, List<CapturedRequest> requests) throws IOException {
    byte[] input = exchange.getRequestBody().readAllBytes();
    Map<String, Object> body = input.length == 0 ? Map.of() : JSON.readValue(input, STRING_OBJECT_MAP);
    requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders().getFirst("Authorization"), body));
    String path = exchange.getRequestURI().getPath();
    if ("DELETE".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    String response = path.endsWith("/screenshot")
        ? "{\"markerId\":\"users\",\"profileReference\":\"admin\",\"reachedUrl\":\"https://app.example/users\",\"artifactReference\":\"artifact://" + body.get("taskId") + "/users/users.png\",\"status\":\"SUCCEEDED\",\"trace\":[]}"
        : path.equals("/internal/sessions")
            ? "{\"sessionId\":\"" + sessionId + "\",\"taskId\":\"" + body.get("taskId") + "\",\"profileReference\":\"admin\",\"status\":\"SUCCEEDED\"}"
            : "{\"status\":\"SUCCEEDED\",\"reachedUrl\":\"https://app.example/users\",\"snapshot\":\"heading Users\",\"trace\":[]}";
    byte[] output = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, output.length);
    exchange.getResponseBody().write(output);
    exchange.close();
  }

  private record CapturedRequest(String method, String path, String authorization, Map<String, Object> body) { }
}
