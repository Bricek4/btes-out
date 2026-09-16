package studio.agent.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Public task request; Platform API validates parameters against the immutable template-version schema. */
public record CreateTaskRequest(UUID projectId, TaskType type, UUID templateVersionId,
                                Map<String, JsonNode> parameters) {
  public static final int MAX_PARAMETER_PROPERTIES = 100;
  public static final int MAX_PARAMETERS_SERIALIZED_BYTES = 64 * 1024;
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
    parameters = Map.copyOf(parameters);
    if (serializedSize(parameters) > MAX_PARAMETERS_SERIALIZED_BYTES) {
      throw new IllegalArgumentException("parameters must not exceed " + MAX_PARAMETERS_SERIALIZED_BYTES + " serialized bytes");
    }
  }

  public TaskStatus initialStatus() { return TaskStatus.QUEUED; }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }

  private static int serializedSize(Map<String, JsonNode> parameters) {
    try {
      return JSON.writeValueAsBytes(parameters).length;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("parameters must be JSON-serializable", exception);
    }
  }
}
