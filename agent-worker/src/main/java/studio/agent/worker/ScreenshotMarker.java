package studio.agent.worker;

import java.util.List;
import java.util.Objects;

public record ScreenshotMarker(String raw, String id, String loginProfileRef, String target, List<String> menuPath,
                               List<String> actions, String caption) {
  public ScreenshotMarker {
    for (String required : List.of(id, loginProfileRef, target, caption)) if (required == null || required.isBlank()) throw new IllegalArgumentException("marker field is required");
    menuPath = menuPath == null ? List.of() : List.copyOf(menuPath);
    actions = actions == null ? List.of() : List.copyOf(actions);
    Objects.requireNonNull(raw, "raw is required");
  }
}
