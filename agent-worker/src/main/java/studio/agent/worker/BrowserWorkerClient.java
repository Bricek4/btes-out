package studio.agent.worker;

import java.util.UUID;

/** Semantic operations only; templates cannot supply CSS selectors or JavaScript. */
public interface BrowserWorkerClient {
  String open(UUID taskId, String baseUrl, String loginProfileReference);
  void login(String sessionId);
  BrowserSnapshot snapshot(String sessionId);
  void navigate(String sessionId, String target);
  void click(String sessionId, String accessibleName);
  String screenshot(String sessionId, String markerId);
  void close(String sessionId);
}
record BrowserSnapshot(String url, String accessibilityText) { }
