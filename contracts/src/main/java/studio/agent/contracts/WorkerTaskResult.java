package studio.agent.contracts;

import java.util.Objects;
import java.util.UUID;

/** Worker completion reported through the workflow boundary. */
public record WorkerTaskResult(UUID taskId, TaskStatus status, String resultReference, String failureCode) {
  public WorkerTaskResult {
    Objects.requireNonNull(taskId, "taskId is required");
    Objects.requireNonNull(status, "status is required");
    if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED) {
      throw new IllegalArgumentException("Worker result must be terminal");
    }
    if (status == TaskStatus.SUCCEEDED) {
      CreateTaskRequest.requireText(resultReference, "resultReference");
      if (failureCode != null) {
        throw new IllegalArgumentException("Succeeded worker result cannot contain a failureCode");
      }
    }
    if (status == TaskStatus.FAILED) {
      CreateTaskRequest.requireText(failureCode, "failureCode");
      if (resultReference != null) {
        throw new IllegalArgumentException("Failed worker result cannot contain a resultReference");
      }
    }
    if (status == TaskStatus.CANCELED && (resultReference != null || failureCode != null)) {
      throw new IllegalArgumentException("Canceled worker result cannot contain result or failure details");
    }
  }
}
