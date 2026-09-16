package studio.agent.worker;

import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;
import studio.agent.contracts.WorkerTaskRequest;

/** Authenticated workflow activity boundary. The request contains opaque references only. */
@RestController
@RequestMapping("/internal/tasks")
public final class AgentTaskExecutionController {
  private final WorkerTaskExecutor execution;

  public AgentTaskExecutionController(WorkerTaskExecutor execution) {
    this.execution = Objects.requireNonNull(execution, "execution service is required");
  }

  @PostMapping("/{taskId}/execute")
  ActivityOutcome execute(@PathVariable String taskId, @RequestBody WorkflowInput input) {
    Objects.requireNonNull(input, "workflow input is required");
    if (!input.taskId().equals(taskId)) throw new IllegalArgumentException("path taskId does not match workflow input");
    WorkerExecutionService.LocalWorkerResult local = execution.execute(input.workerRequest(), input.approvedReference());
    if (local.completion() == null) {
      if (local.approvalRequest() != null) {
        return new ActivityOutcome(TaskStatus.WAITING_FOR_APPROVAL, null, null, local.approvalRequest());
      }
      return new ActivityOutcome(TaskStatus.FAILED, null, "AGENT_RESULT_INVALID", null);
    }
    return new ActivityOutcome(local.completion().status(), local.completion().resultReference(),
        local.completion().failureCode(), null);
  }

  public record WorkflowInput(String taskId, String projectId, TaskType type, String sourceReference,
      String templateVersionReference, String parametersReference, String providerProfileReference,
      boolean requiresApproval, String approvedReference) {
    public WorkflowInput(String taskId, String projectId, TaskType type, String sourceReference,
        String templateVersionReference, String parametersReference, String providerProfileReference,
        boolean requiresApproval) {
      this(taskId, projectId, type, sourceReference, templateVersionReference, parametersReference,
          providerProfileReference, requiresApproval, null);
    }
    public WorkflowInput {
      requireText(taskId, "taskId");
      requireText(projectId, "projectId");
      Objects.requireNonNull(type, "type is required");
      requireText(sourceReference, "sourceReference");
      requireText(templateVersionReference, "templateVersionReference");
      requireText(parametersReference, "parametersReference");
      requireText(providerProfileReference, "providerProfileReference");
      if (approvedReference != null && (approvedReference.isBlank() || approvedReference.length() > 2_048
          || !approvedReference.startsWith("approval://screenshot-route/"))) {
        throw new IllegalArgumentException("approvedReference is invalid");
      }
    }
    WorkerTaskRequest workerRequest() {
      try {
        return new WorkerTaskRequest(UUID.fromString(taskId), UUID.fromString(projectId), type,
            sourceReference, templateVersionReference, parametersReference, providerProfileReference);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("taskId and projectId must be UUIDs");
      }
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record ActivityOutcome(TaskStatus status, String artifactReference, String failureCode,
                                WorkerExecutionService.ApprovalBridge approval) {
    public ActivityOutcome {
      Objects.requireNonNull(status, "status is required");
      if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED
          && status != TaskStatus.WAITING_FOR_APPROVAL) {
        throw new IllegalArgumentException("activity outcome status is invalid");
      }
      if (status == TaskStatus.SUCCEEDED && (artifactReference == null || artifactReference.isBlank() || failureCode != null)) {
        throw new IllegalArgumentException("successful activity must contain only an artifact reference");
      }
      if (status == TaskStatus.FAILED && (failureCode == null || failureCode.isBlank() || artifactReference != null)) {
        throw new IllegalArgumentException("failed activity must contain only a failure code");
      }
      if (status == TaskStatus.CANCELED && (artifactReference != null || failureCode != null)) {
        throw new IllegalArgumentException("canceled activity cannot contain result details");
      }
      if (status == TaskStatus.WAITING_FOR_APPROVAL
          && (approval == null || artifactReference != null || failureCode != null)) {
        throw new IllegalArgumentException("approval outcome must contain only the approval bridge");
      }
      if (approval != null && status != TaskStatus.WAITING_FOR_APPROVAL) {
        throw new IllegalArgumentException("approval bridge requires the waiting status");
      }
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 2_048) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
