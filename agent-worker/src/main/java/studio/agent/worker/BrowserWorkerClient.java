package studio.agent.worker;

import java.util.Set;
import java.util.UUID;

/** Semantic operations only; templates cannot supply CSS selectors or JavaScript. */
public interface BrowserWorkerClient {
  String open(UUID taskId, String baseUrl, String loginProfileReference);
  void login(String sessionId);
  BrowserSnapshot snapshot(String sessionId);
  void navigate(String sessionId, String target);
  void click(String sessionId, SemanticLocator locator);
  default void clickMenuItem(String sessionId, String accessibleName) {
    click(sessionId, new SemanticLocator("role", "menuitem", accessibleName));
  }
  void fill(String sessionId, SemanticLocator locator, String value);
  void select(String sessionId, SemanticLocator locator, String value);
  void check(String sessionId, SemanticLocator locator);
  void uncheck(String sessionId, SemanticLocator locator);
  void waitFor(String sessionId, SemanticLocator locator);
  String screenshot(String sessionId, String markerId, String caption);
  default String screenshot(String sessionId, String markerId) { return screenshot(sessionId, markerId, markerId); }
  void close(String sessionId);
}
record BrowserSnapshot(String url, String accessibilityText) { }

/** Wire-compatible with the shared LoginLocator contract; selector strings are not accepted. */
record SemanticLocator(String kind, String role, String name) implements java.io.Serializable {
  private static final Set<String> KINDS = Set.of("role", "label", "test-id");
  SemanticLocator {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException("semantic locator kind is invalid");
    if (name == null || name.isBlank() || name.length() > 160) {
      throw new IllegalArgumentException("semantic locator name is invalid");
    }
    name = name.trim();
    if ("role".equals(kind)) {
      if (role == null || role.isBlank() || role.length() > 80) {
        throw new IllegalArgumentException("semantic locator role is required");
      }
      role = role.trim();
    } else if (role != null) {
      throw new IllegalArgumentException("semantic locator role is only valid for role locators");
    }
  }
}
