package studio.agent.contracts;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Public task request. Platform API validates parameters against the immutable template-version
 * schema and confirms that a selected provider profile belongs to the caller.
 */
public record CreateTaskRequest(UUID projectId, TaskType type, UUID templateVersionId,
                                Map<String, JsonNode> parameters, UUID providerProfileId,
                                String modelId) {
  public static final int MAX_PARAMETER_PROPERTIES = 100;
  public static final int MAX_PARAMETERS_SERIALIZED_BYTES = 64 * 1024;
  public static final int MAX_MODEL_ID_LENGTH = 255;
  private static final ObjectMapper JSON = new ObjectMapper();

  public CreateTaskRequest {
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(type, "type is required");
    Objects.requireNonNull(templateVersionId, "templateVersionId is required");
    if (parameters == null) throw new IllegalArgumentException("parameters is required");
    if (parameters.size() > MAX_PARAMETER_PROPERTIES) {
      throw new IllegalArgumentException("parameters must not exceed " + MAX_PARAMETER_PROPERTIES + " properties");
    }
    parameters.forEach((name, value) -> {
      requireText(name, "parameter name");
      Objects.requireNonNull(value, "parameter value is required");
    });
    parameters = copyParameters(parameters);
    if (serializedSize(parameters) > MAX_PARAMETERS_SERIALIZED_BYTES) {
      throw new IllegalArgumentException("parameters must not exceed " + MAX_PARAMETERS_SERIALIZED_BYTES + " serialized bytes");
    }
    if ((providerProfileId == null) != (modelId == null)) {
      throw new IllegalArgumentException("providerProfileId and modelId must be supplied together");
    }
    if (modelId != null) {
      requireText(modelId, "modelId");
      if (modelId.length() > MAX_MODEL_ID_LENGTH) {
        throw new IllegalArgumentException("modelId must not exceed " + MAX_MODEL_ID_LENGTH + " characters");
      }
    }
  }

  public TaskStatus initialStatus() { return TaskStatus.QUEUED; }

  @Override
  public Map<String, JsonNode> parameters() {
    return copyParameters(parameters);
  }

  public int parametersSerializedSize() {
    return serializedSize(parameters);
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }

  private static int serializedSize(Map<String, JsonNode> parameters) {
    try {
      return JSON.writeValueAsBytes(parameters).length;
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("parameters must be JSON-serializable", exception);
    }
  }

  private static Map<String, JsonNode> copyParameters(Map<String, JsonNode> parameters) {
    var copy = new LinkedHashMap<String, JsonNode>();
    parameters.forEach((name, value) -> copy.put(name, value.deepCopy()));
    return Map.copyOf(copy);
  }
}
