package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class AgentPlatformClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() { };

  @Test void fetchesTransientProviderAndPublishesOnlyAgentKindsWithMatchingReservation() throws Exception {
    UUID taskId = UUID.randomUUID();
    UUID artifactId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    List<CapturedRequest> requests = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    server.createContext("/", exchange -> handlePlatform(exchange, artifactId, reservationId, requests));
    server.start();
    try {
      String base = "http://127.0.0.1:" + port;
      var client = new AgentPlatformClient(base, "agent-token");
      AgentTaskContext context = client.context(taskId);
      assertEquals(taskId, context.taskId());
      assertEquals("https://app.example", context.baseUrl());
      assertEquals(Set.of("admin"), context.loginProfileReferences());
      assertEquals("docs/users.md", context.parameters().get("outputPath"));
      ProviderConnection provider = client.provider(taskId);
      assertEquals("deepseek-chat", provider.model());
      assertEquals("secret-api-key", provider.apiKey());
      assertFalse(provider.toString().contains("secret-api-key"));

      assertArrayEquals("source route /users".getBytes(StandardCharsets.UTF_8),
          client.fetchSource(URI.create(base + "/source")));

      byte[] artifact = "# Users".getBytes(StandardCharsets.UTF_8);
      var published = client.publish(taskId, new AgentPlatformClient.ArtifactUpload(
          "docs/users.md", AgentPlatformClient.ArtifactKind.DOC, "text/markdown", artifact,
          "{\"markers\":[]}"));
      var repeated = client.publish(taskId, new AgentPlatformClient.ArtifactUpload(
          "docs/users.md", AgentPlatformClient.ArtifactKind.DOC, "text/markdown", artifact,
          "{\"markers\":[]}"));
      assertEquals(artifactId, published.artifactId());
      assertEquals(published, repeated);
      assertEquals("artifact://docs/" + artifactId, published.reference());

      CapturedRequest presign = requests.stream().filter(r -> r.path().endsWith("/presign")).findFirst().orElseThrow();
      assertEquals("DOC", presign.body().get("kind"));
      assertTrue(String.valueOf(presign.body().get("idempotencyKey")).matches("[0-9a-f]{64}"));
      assertEquals((long) artifact.length, ((Number) presign.body().get("sizeBytes")).longValue());
      assertEquals(1, requests.stream().filter(r -> r.path().endsWith("/presign")).count());
      CapturedRequest complete = requests.stream().filter(r -> r.path().endsWith("/complete")).findFirst().orElseThrow();
      assertEquals(reservationId.toString(), complete.body().get("reservationId"));
      CapturedRequest source = requests.stream().filter(r -> r.path().equals("/source")).findFirst().orElseThrow();
      assertNull(source.authorization());
      CapturedRequest upload = requests.stream().filter(r -> r.path().equals("/put/object")).findFirst().orElseThrow();
      assertNull(upload.authorization());
      assertEquals("B1LRNEZKIkWmMtQ6wYTK5MPmwiuGAhL7tgrKtStVBko=", upload.checksumSha256());
      assertTrue(requests.stream().filter(r -> r.path().startsWith("/internal/"))
          .allMatch(r -> "Bearer agent-token".equals(r.authorization())));

      assertThrows(IllegalArgumentException.class, () -> client.publish(taskId,
          new AgentPlatformClient.ArtifactUpload("screenshots/x.png", null, "image/png", new byte[] {1}, "{}")));
    } finally {
      server.stop(0);
    }
  }

  @Test void sanitizesPlatformFailureBodiesThatCouldContainProviderCredentials() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] output = "credential=must-not-escape".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, output.length);
      exchange.getResponseBody().write(output);
      exchange.close();
    });
    server.start();
    try {
      var client = new AgentPlatformClient("http://127.0.0.1:" + server.getAddress().getPort(), "agent-token");
      RuntimeException failure = assertThrows(RuntimeException.class, () -> client.provider(UUID.randomUUID()));
      assertEquals("PROVIDER_CREDENTIAL_UNAVAILABLE", failure.getMessage());
      assertFalse(failure.toString().contains("must-not-escape"));
    } finally {
      server.stop(0);
    }
  }

  @Test void rejectsAPresignResponseThatDoesNotEchoTheBoundIdempotencyKey() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] input = exchange.getRequestBody().readAllBytes();
      String path = exchange.getRequestURI().getPath();
      if (!path.endsWith("/presign")) throw new AssertionError("unexpected path " + path);
      byte[] output = ("{\"artifactId\":\"" + UUID.randomUUID()
          + "\",\"reservationId\":\"" + UUID.randomUUID()
          + "\",\"idempotencyKey\":\"wrong\",\"putUrl\":\"http://127.0.0.1:1/unused\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, output.length);
      exchange.getResponseBody().write(output);
      exchange.close();
    });
    server.start();
    try {
      var client = new AgentPlatformClient("http://127.0.0.1:" + server.getAddress().getPort(), "agent-token");
      var upload = new AgentPlatformClient.ArtifactUpload("docs/a.md", AgentPlatformClient.ArtifactKind.DOC,
          "text/markdown", new byte[] {1}, "{}");
      RuntimeException failure = assertThrows(RuntimeException.class,
          () -> client.publish(UUID.randomUUID(), upload));
      assertEquals("ARTIFACT_IDEMPOTENCY_MISMATCH", failure.getMessage());
    } finally {
      server.stop(0);
    }
  }

  private static void handlePlatform(HttpExchange exchange, UUID artifactId, UUID reservationId,
      List<CapturedRequest> requests) throws IOException {
    byte[] input = exchange.getRequestBody().readAllBytes();
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    Map<String, Object> body = input.length == 0 || contentType == null || !contentType.contains("json")
        ? Map.of() : JSON.readValue(input, STRING_OBJECT_MAP);
    requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders().getFirst("Authorization"),
        exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"), body));
    String path = exchange.getRequestURI().getPath();
    String response;
    if (path.equals("/source")) response = "source route /users";
    else if (path.contains("/worker-context/agent/")) {
      String taskId = path.substring(path.lastIndexOf('/') + 1);
      response = "{\"taskId\":\"" + taskId + "\",\"sourceUrl\":\"http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/source\",\"template\":{\"id\":\"" + UUID.randomUUID() + "\",\"format\":\"markdown\",\"markdown\":\"# {{content}}\",\"html\":\"\",\"css\":\"\",\"schema\":\"{}\"},\"parameters\":\"{\\\"outputPath\\\":\\\"docs/users.md\\\",\\\"baseUrl\\\":\\\"https://app.example\\\"}\",\"providerProfileId\":\"" + UUID.randomUUID() + "\",\"modelId\":\"deepseek-chat\",\"loginProfiles\":[{\"id\":\"" + UUID.randomUUID() + "\",\"reference\":\"admin\",\"name\":\"Admin\"}]}";
    }
    else if (path.endsWith("/provider-credential")) response = "{\"endpoint\":\"https://api.deepseek.com\",\"model\":\"deepseek-chat\",\"apiKey\":\"secret-api-key\",\"options\":{\"enable_thinking\":false}}";
    else if (path.endsWith("/presign")) response = "{\"artifactId\":\"" + artifactId
        + "\",\"reservationId\":\"" + reservationId + "\",\"idempotencyKey\":\""
        + body.get("idempotencyKey") + "\",\"putUrl\":\"http://127.0.0.1:"
        + exchange.getLocalAddress().getPort() + "/put/object\"}";
    else if (path.equals("/put/object")) response = "";
    else if (path.endsWith("/complete")) response = "{\"artifactId\":\"" + artifactId + "\",\"completed\":true}";
    else throw new AssertionError("unexpected path " + path);
    byte[] output = response.getBytes(StandardCharsets.UTF_8);
    if (path.contains("/worker-context/agent/") || path.endsWith("/provider-credential") || path.endsWith("/presign") || path.endsWith("/complete")) {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
    } else if (path.equals("/source")) {
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
    }
    exchange.sendResponseHeaders(path.equals("/put/object") ? 200 : 200, output.length);
    exchange.getResponseBody().write(output);
    exchange.close();
  }

  private record CapturedRequest(String method, String path, String authorization,
                                 String checksumSha256, Map<String, Object> body) { }
}
