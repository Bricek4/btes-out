package studio.agent.worker;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import studio.agent.contracts.TaskType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transient intent classifier. It has no task creation dependency by design. */
public final class ChatDraftParser {
  private static final int MAX_MESSAGE_CHARS = 20_000;
  private static final int MAX_MODEL_OUTPUT_CHARS = 100_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> FIELDS = Set.of("workflowType", "parameters", "summary");
  private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
      "(?im)(\\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token|authorization)\\b\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");

  private final ArtifactGenerationService.ModelGateway model;

  public ChatDraftParser() { this.model = null; }
  public ChatDraftParser(ArtifactGenerationService.ModelGateway model) {
    this.model = java.util.Objects.requireNonNull(model, "model is required");
  }

  public TaskDraft parse(String message) {
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    if (message.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("message is too large");
    if (model != null) return parseModelDraft(message);
    String normalized = message.toLowerCase(Locale.ROOT);
    TaskType type = normalized.contains("用户手册") || normalized.contains("操作手册") || normalized.contains("手册") ? TaskType.USER_GUIDE
        : normalized.contains("screenshot") || normalized.contains("screen shot") || normalized.contains("截图") ? TaskType.SCREENSHOT
        : normalized.contains("html") || normalized.contains("web page") ? TaskType.HTML
        : normalized.contains("guide") || normalized.contains("how to") ? TaskType.USER_GUIDE : TaskType.PROJECT_DOCS;
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("goal", message.strip());
    parameters.put("outputPath", switch (type) {
      case HTML -> "site/index.html";
      case USER_GUIDE -> "docs/user-guide.md";
      case SCREENSHOT -> "manifests/screenshots.json";
      case PROJECT_DOCS -> "docs/README.md";
    });
    return new TaskDraft(type, Map.copyOf(parameters), "Draft " + type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " artifact", null);
  }

  private TaskDraft parseModelDraft(String message) {
    String redacted = SECRET_ASSIGNMENT.matcher(message).replaceAll("$1[REDACTED]");
    String prompt = "Classify the user's request as exactly one of PROJECT_DOCS, USER_GUIDE, HTML, or SCREENSHOT. "
        + "Return one JSON object with exactly workflowType, parameters, and summary. Parameters must be a flat string map "
        + "for an editable draft. Do not create, launch, identify, or reference a task. User request:\n" + redacted;
    String completion = model.complete(prompt);
    if (completion == null || completion.isBlank() || completion.length() > MAX_MODEL_OUTPUT_CHARS) {
      throw new IllegalArgumentException("model returned an invalid task draft");
    }
    try {
      JsonNode root = JSON.readTree(completion);
      if (!root.isObject()) throw new IllegalArgumentException("model task draft must be a JSON object");
      for (var property : root.properties()) {
        if (!FIELDS.contains(property.getKey())) throw new IllegalArgumentException("model task draft contains an unknown field");
      }
      if (root.size() != FIELDS.size()) throw new IllegalArgumentException("model task draft is incomplete");
      TaskType type = TaskType.valueOf(requiredText(root, "workflowType", 32));
      JsonNode rawParameters = root.get("parameters");
      if (rawParameters == null || !rawParameters.isObject() || rawParameters.isEmpty() || rawParameters.size() > 32) {
        throw new IllegalArgumentException("model task draft parameters are invalid");
      }
      var parameters = new LinkedHashMap<String, String>();
      for (var property : rawParameters.properties()) {
        if (property.getKey().isBlank() || property.getKey().length() > 80 || !property.getValue().isString()
            || property.getValue().asString().isBlank() || property.getValue().asString().length() > 2_000) {
          throw new IllegalArgumentException("model task draft parameters are invalid");
        }
        parameters.put(property.getKey(), property.getValue().asString().trim());
      }
      return new TaskDraft(type, parameters, requiredText(root, "summary", 500), null);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("model returned an invalid task draft");
    }
  }

  private static String requiredText(JsonNode root, String field, int maxLength) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > maxLength) {
      throw new IllegalArgumentException("model task draft " + field + " is invalid");
    }
    return value.asString().trim();
  }
}
