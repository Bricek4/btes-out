package studio.agent.worker;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import studio.agent.contracts.TaskType;

/** Transient intent classifier. It has no task creation dependency by design. */
public final class ChatDraftParser {
  public TaskDraft parse(String message) {
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    String normalized = message.toLowerCase(Locale.ROOT);
    TaskType type = normalized.contains("screenshot") || normalized.contains("screen shot") ? TaskType.SCREENSHOT
        : normalized.contains("html") || normalized.contains("web page") ? TaskType.HTML
        : normalized.contains("guide") || normalized.contains("how to") ? TaskType.USER_GUIDE : TaskType.PROJECT_DOCS;
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("goal", message.strip());
    parameters.put("outputPath", type == TaskType.HTML ? "site/index.html" : "docs/README.md");
    return new TaskDraft(type, Map.copyOf(parameters), "Draft " + type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " artifact", null);
  }
}
