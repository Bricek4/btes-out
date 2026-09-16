package studio.agent.workflow;

import java.util.Objects;
import studio.agent.contracts.TaskStatus;

/** Stable terminal response returned to the task orchestration caller. */
public record TaskWorkflowResult(String taskId, TaskStatus status, String artifactReference, String failureCode) {
  public TaskWorkflowResult {
    WorkflowInput.requirePathSegment(taskId, "taskId");
    Objects.requireNonNull(status, "status is required");
    if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED) {
      throw new IllegalArgumentException("workflow result must be terminal");
    }
    if (status == TaskStatus.SUCCEEDED && (artifactReference == null || artifactReference.isBlank() || failureCode != null)) {
      throw new IllegalArgumentException("successful result must contain only an artifact reference");
    }
    if (status == TaskStatus.FAILED && (failureCode == null || failureCode.isBlank() || artifactReference != null)) {
      throw new IllegalArgumentException("failed result must contain only a failure code");
    }
    if (status == TaskStatus.CANCELED && (artifactReference != null || failureCode == null || failureCode.isBlank())) {
      throw new IllegalArgumentException("canceled result must contain a failure code and no artifact");
    }
    if (artifactReference != null) {
      WorkflowInput.requireOpaqueReference(artifactReference, "artifact://", "artifactReference");
    }
    if (failureCode != null) WorkflowInput.requireMachineCode(failureCode, "failureCode");
  }
}
