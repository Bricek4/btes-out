package studio.agent.worker;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Browser requests carry task and semantic locator data; profile secrets never cross this client. */
public final class RestBrowserWorkerClient implements BrowserWorkerClient {
  private static final Logger LOG = LoggerFactory.getLogger(RestBrowserWorkerClient.class);
  private final RestClient http;
  private final String token;
  private final Map<String, UUID> sessionTasks = new ConcurrentHashMap<>();

  public RestBrowserWorkerClient(String baseUrl, String agentWorkerToken) {
    // Opening a Playwright context is a cold operation in a constrained container and can take
    // longer than the framework's reactive client's default 10 second read timeout. Use the JDK
    // client with an explicit bounded deadline so a slow browser cannot fail as a misleading
    // transport error, while requests still have a finite upper bound.
    var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build());
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    this.http = RestClient.builder().requestFactory(requestFactory)
        .baseUrl(Objects.requireNonNull(baseUrl)).build();
    this.token = requireText(agentWorkerToken);
  }

  @Override public String open(UUID taskId, String baseUrl, String profile) {
    Map<?, ?> response = post("/internal/sessions", Map.of(
        "taskId", taskId.toString(), "baseUrl", baseUrl, "loginProfileReference", profile));
    requireSucceeded(response, "open");
    String id = requireText(string(response, "sessionId"));
    sessionTasks.put(id, taskId);
    return id;
  }

  @Override public void login(String sessionId) {
    requireSucceeded(post(sessionPath(sessionId, "login"), Map.of("taskId", task(sessionId).toString())), "login");
  }

  @Override public BrowserSnapshot snapshot(String sessionId) {
    Map<?, ?> response;
    try {
      response = http.get().uri(sessionPath(sessionId, "snapshot") + "?taskId={taskId}", task(sessionId))
          .header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new BrowserOperationException("BROWSER_HTTP_FAILED", "snapshot");
    }
    requireSucceeded(response, "snapshot");
    return new BrowserSnapshot(requireText(string(response, "reachedUrl")), requireText(string(response, "snapshot")));
  }

  @Override public void navigate(String sessionId, String target) {
    Map<?, ?> response = post(sessionPath(sessionId, "navigate"), Map.of(
        "taskId", task(sessionId).toString(), "target", requireText(target), "expected", Map.of()));
    requireSucceeded(response, "navigate");
  }

  @Override public void click(String sessionId, SemanticLocator locator) {
    locatorCommand(sessionId, "click", locator, null);
  }

  @Override public void clickMenuItem(String sessionId, String accessibleName) {
    click(sessionId, new SemanticLocator("role", "menuitem", accessibleName));
  }

  @Override public void fill(String sessionId, SemanticLocator locator, String value) {
    locatorCommand(sessionId, "fill", locator, requireText(value));
  }

  @Override public void select(String sessionId, SemanticLocator locator, String value) {
    locatorCommand(sessionId, "select", locator, requireText(value));
  }

  @Override public void check(String sessionId, SemanticLocator locator) {
    locatorCommand(sessionId, "check", locator, null);
  }

  @Override public void uncheck(String sessionId, SemanticLocator locator) {
    locatorCommand(sessionId, "uncheck", locator, null);
  }

  @Override public void waitFor(String sessionId, SemanticLocator locator) {
    locatorCommand(sessionId, "wait", locator, null);
  }

  @Override public String screenshot(String sessionId, String markerId, String caption) {
    Map<?, ?> response = post(sessionPath(sessionId, "screenshot"), Map.of(
        "taskId", task(sessionId).toString(), "markerId", markerId,
        "caption", requireText(caption), "filename", markerId + ".png",
        "expected", Map.of()));
    requireSucceeded(response, "screenshot");
    return requireText(string(response, "artifactReference"));
  }

  @Override public void close(String sessionId) {
    UUID task = sessionTasks.remove(sessionId);
    if (task == null) return;
    try {
      http.delete().uri(sessionPath(sessionId, "") + "?taskId={taskId}", task)
          .header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().toBodilessEntity();
    } catch (RuntimeException failure) {
      throw new BrowserOperationException("BROWSER_HTTP_FAILED", "close");
    }
  }

  private void locatorCommand(String sessionId, String action, SemanticLocator locator, String value) {
    Objects.requireNonNull(locator, "semantic locator is required");
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("taskId", task(sessionId).toString());
    var locatorBody = new java.util.LinkedHashMap<String, String>();
    locatorBody.put("kind", locator.kind());
    if (locator.role() != null) locatorBody.put("role", locator.role());
    locatorBody.put("name", locator.name());
    body.put("locator", locatorBody);
    if (value != null) body.put("value", value);
    body.put("expected", Map.of());
    Map<?, ?> response = post(sessionPath(sessionId, action), body);
    requireSucceeded(response, action + ":" + locator.kind() + ":" + stableNameHash(locator.name()));
  }

  private UUID task(String sessionId) {
    UUID task = sessionTasks.get(sessionId);
    if (task == null) throw new IllegalArgumentException("browser session is not owned by this task");
    return task;
  }

  private String sessionPath(String sessionId, String operation) {
    requireText(sessionId);
    if (!sessionId.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("browser session id is invalid");
    return "/internal/sessions/" + sessionId + (operation == null || operation.isEmpty() ? "" : "/" + operation);
  }

  private String bearer() { return "Bearer " + token; }

  @SuppressWarnings("unchecked")
  private Map<?, ?> post(String path, Object body) {
    Map<?, ?> response;
    try {
      response = http.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer())
          .contentType(MediaType.APPLICATION_JSON).body(body)
          .retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new BrowserOperationException("BROWSER_HTTP_FAILED", operationFromPath(path));
    }
    if (response == null) throw new IllegalStateException("browser worker returned an empty response");
    return response;
  }

  private static void requireSucceeded(Map<?, ?> response, String operation) {
    Object status = response.get("status");
    if (status != null && !"SUCCEEDED".equals(String.valueOf(status))) {
      Object error = response.get("error");
      Object code = error instanceof Map<?, ?> values ? values.get("code") : null;
      String safeCode = code == null ? "BROWSER_OPERATION_FAILED" : String.valueOf(code);
      LOG.warn("browser operation rejected: operation={}, code={}", operation, safeCode);
      throw new BrowserOperationException(safeCode, operation);
    }
  }

  private static String operationFromPath(String path) {
    if (path == null || path.isBlank()) return "unknown";
    int slash = path.lastIndexOf('/');
    return slash < 0 || slash == path.length() - 1 ? "request" : path.substring(slash + 1);
  }

  private static String stableNameHash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String string(Map<?, ?> values, String key) {
    Object value = values.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank() || "null".equals(value)) {
      throw new IllegalArgumentException("browser response is missing required text");
    }
    return value;
  }

  static final class BrowserOperationException extends IllegalStateException {
    private final String code;
    private final String operation;
    BrowserOperationException(String code) { this(code, "unknown"); }
    BrowserOperationException(String code, String operation) { super(code); this.code = code; this.operation = operation; }
    String code() { return code; }
    String operation() { return operation; }
  }
}
