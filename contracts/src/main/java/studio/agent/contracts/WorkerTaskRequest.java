package studio.agent.contracts;

import java.util.Objects;
import java.util.UUID;

/** Worker-safe command: it carries references, never provider secrets or prompt content. */
public record WorkerTaskRequest(UUID taskId, UUID projectId, TaskType type, String inputReference) {
  public WorkerTaskRequest {
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(type, "type is required");
    CreateTaskRequest.requireText(inputReference, "inputReference");
  }
}
