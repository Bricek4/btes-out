package studio.agent.contracts;

import java.util.Objects;
import java.util.UUID;

/** API command after the Idempotency-Key header has been normalized by platform-api. */
public record CreateTaskRequest(UUID projectId, TaskType type, String idempotencyKey, String inputReference) {
  public CreateTaskRequest {
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(type, "type is required");
    requireText(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.length() > 255) {
      throw new IllegalArgumentException("idempotencyKey must not exceed 255 characters");
    }
    requireText(inputReference, "inputReference");
  }

  public TaskStatus initialStatus() {
    return TaskStatus.QUEUED;
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
