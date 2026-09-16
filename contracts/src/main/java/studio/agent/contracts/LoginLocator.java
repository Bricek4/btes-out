package studio.agent.contracts;

import java.util.Set;

/** Declarative browser locator; CSS, XPath and script selectors are deliberately unsupported. */
public record LoginLocator(String kind, String role, String name) {
  public LoginLocator {
    if (!Set.of("role", "label", "test-id").contains(kind)) throw new IllegalArgumentException("locator kind is invalid");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("locator name is required");
    if ("role".equals(kind) && (role == null || role.isBlank())) throw new IllegalArgumentException("locator role is required");
    if (!"role".equals(kind) && role != null) throw new IllegalArgumentException("locator role is only valid for role locators");
  }
}
