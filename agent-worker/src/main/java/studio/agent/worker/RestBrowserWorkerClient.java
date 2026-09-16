package studio.agent.worker;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** Browser credentials remain inside browser-worker. This client transmits only a profile reference. */
public final class RestBrowserWorkerClient implements BrowserWorkerClient {
  private final RestClient http;
  private final String token;
  public RestBrowserWorkerClient(String baseUrl, String agentWorkerToken) {
    this.http = RestClient.builder().baseUrl(Objects.requireNonNull(baseUrl)).build();
    this.token = requireText(agentWorkerToken);
  }
  @Override public String open(UUID taskId, String baseUrl, String profile) {
    Map<?, ?> response = post("/internal/sessions", Map.of("taskId", taskId.toString(), "baseUrl", baseUrl, "loginProfileReference", profile));
    return requireText(String.valueOf(response.get("id")));
  }
  @Override public void login(String sessionId) { post("/internal/sessions/" + sessionId + "/login", Map.of()); }
  @Override public BrowserSnapshot snapshot(String sessionId) {
    Map<?, ?> response = http.get().uri("/internal/sessions/{id}/snapshot", sessionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Map.class);
    return new BrowserSnapshot(String.valueOf(response.get("url")), String.valueOf(response.get("accessibilityText")));
  }
  @Override public void navigate(String sessionId, String target) { command(sessionId, "navigate", Map.of("target", target)); }
  @Override public void click(String sessionId, String name) { command(sessionId, "click", Map.of("role", "button", "name", name)); }
  public void fill(String sessionId, String label, String value) { command(sessionId, "fill", Map.of("label", label, "value", value)); }
  public void select(String sessionId, String label, String value) { command(sessionId, "select", Map.of("label", label, "value", value)); }
  public void check(String sessionId, String label) { command(sessionId, "check", Map.of("label", label)); }
  public void uncheck(String sessionId, String label) { command(sessionId, "uncheck", Map.of("label", label)); }
  public void waitFor(String sessionId, String state) { command(sessionId, "wait", Map.of("state", state)); }
  @Override public String screenshot(String sessionId, String markerId) { return requireText(String.valueOf(post("/internal/sessions/" + sessionId + "/screenshot", Map.of("markerId", markerId)).get("artifactReference"))); }
  @Override public void close(String sessionId) { http.delete().uri("/internal/sessions/{id}", sessionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toBodilessEntity(); }
  private void command(String sessionId, String action, Map<String, String> body) { post("/internal/sessions/" + sessionId + "/" + action, body); }
  @SuppressWarnings("unchecked") private Map<?, ?> post(String path, Object body) { return http.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body(body).retrieve().body(Map.class); }
  private static String requireText(String value) { if (value == null || value.isBlank() || "null".equals(value)) throw new IllegalArgumentException("browser response is missing required text"); return value; }
}
