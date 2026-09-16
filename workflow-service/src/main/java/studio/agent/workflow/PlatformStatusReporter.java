package studio.agent.workflow;

import studio.agent.contracts.TaskStatus;

interface PlatformStatusReporter {
  void report(PlatformStatusUpdate update);

  static PlatformStatusReporter noOp() {
    return update -> { };
  }
}

record PlatformStatusUpdate(String taskId, TaskStatus status, Integer progress,
                            String resultReference, String failureCode) {
  PlatformStatusUpdate {
    WorkflowInput.requirePathSegment(taskId, "taskId");
    if (status == null || status == TaskStatus.QUEUED || status == TaskStatus.PAUSED) {
      throw new IllegalArgumentException("reported status is invalid");
    }
    if (progress != null && (progress < 0 || progress > 100)) {
      throw new IllegalArgumentException("progress must be between 0 and 100");
    }
    if (resultReference != null) {
      WorkflowInput.requireOpaqueReference(resultReference, "artifact://", "resultReference");
    }
    if (failureCode != null) WorkflowInput.requireMachineCode(failureCode, "failureCode");
    if (status == TaskStatus.SUCCEEDED && (resultReference == null || failureCode != null)) {
      throw new IllegalArgumentException("success requires only a result reference");
    }
    if (status == TaskStatus.FAILED && (failureCode == null || resultReference != null)) {
      throw new IllegalArgumentException("failure requires only a failure code");
    }
    if (status != TaskStatus.SUCCEEDED && resultReference != null) {
      throw new IllegalArgumentException("non-success status cannot contain a result reference");
    }
  }
}
