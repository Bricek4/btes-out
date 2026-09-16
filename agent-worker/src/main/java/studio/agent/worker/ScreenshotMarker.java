package studio.agent.worker;

import java.util.List;
import java.util.Objects;

public record ScreenshotMarker(String raw, String id, String loginProfileRef, String target, List<String> menuPath,
                               List<ScreenshotAction> actions, String routePath, String caption) implements java.io.Serializable {
  public ScreenshotMarker {
    for (String required : List.of(id, loginProfileRef, target, caption)) if (required == null || required.isBlank()) throw new IllegalArgumentException("marker field is required");
    menuPath = menuPath == null ? List.of() : List.copyOf(menuPath);
    actions = actions == null ? List.of() : List.copyOf(actions);
    if (routePath != null && (!routePath.startsWith("/") || routePath.startsWith("//")
        || routePath.contains("..") || routePath.contains("\\") || routePath.contains("?"))) {
      throw new IllegalArgumentException("routePath must be an application-relative path");
    }
    Objects.requireNonNull(raw, "raw is required");
  }
}

record ScreenshotAction(String type, SemanticLocator locator, String value) implements java.io.Serializable {
  private static final java.util.Set<String> TYPES = java.util.Set.of(
      "click", "fill", "select", "check", "uncheck", "wait");
  ScreenshotAction {
    if (!TYPES.contains(type)) throw new IllegalArgumentException("screenshot action type is invalid");
    Objects.requireNonNull(locator, "screenshot action locator is required");
    if (java.util.Set.of("fill", "select").contains(type)) {
      if (value == null || value.isBlank() || value.length() > 500) {
        throw new IllegalArgumentException("screenshot action value is required");
      }
    } else if (value != null) {
      throw new IllegalArgumentException("screenshot action value is not allowed");
    }
  }
}
