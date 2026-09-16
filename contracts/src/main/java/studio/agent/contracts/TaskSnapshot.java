package studio.agent.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable task read model shared by REST, SSE, workflows, and workers. */
public record TaskSnapshot(UUID taskId, UUID projectId, TaskType type, TaskStatus status,
                           Instant createdAt, Instant updatedAt,
                           String resultReference, String failureCode) {
  public TaskSnapshot {
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(type, "type is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
  }
}
