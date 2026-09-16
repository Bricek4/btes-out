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
  private final WorkerExecutionService execution;

  public AgentTaskExecutionController(WorkerExecutionService execution) {
    this.execution = Objects.requireNonNull(execution, "execution service is required");
  }

  @PostMapping("/{taskId}/execute")
  ActivityOutcome execute(@PathVariable String taskId, @RequestBody WorkflowInput input) {
    Objects.requireNonNull(input, "workflow input is required");
    if (!input.taskId().equals(taskId)) throw new IllegalArgumentException("path taskId does not match workflow input");
    WorkerExecutionService.LocalWorkerResult local = execution.execute(input.workerRequest());
    if (local.completion() == null) {
      return new ActivityOutcome(TaskStatus.FAILED, null,
          local.approvalReference() == null ? "AGENT_RESULT_INVALID" : "SCREENSHOT_APPROVAL_REQUIRED");
    }
    return new ActivityOutcome(local.completion().status(), local.completion().resultReference(),
        local.completion().failureCode());
  }

  public record WorkflowInput(String taskId, String projectId, TaskType type, String sourceReference,
      String templateVersionReference, String parametersReference, String providerProfileReference,
      boolean requiresApproval) {
    public WorkflowInput {
      requireText(taskId, "taskId");
      requireText(projectId, "projectId");
      Objects.requireNonNull(type, "type is required");
      requireText(sourceReference, "sourceReference");
      requireText(templateVersionReference, "templateVersionReference");
      requireText(parametersReference, "parametersReference");
      requireText(providerProfileReference, "providerProfileReference");
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

  public record ActivityOutcome(TaskStatus status, String artifactReference, String failureCode) {
    public ActivityOutcome {
      Objects.requireNonNull(status, "status is required");
      if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED) {
        throw new IllegalArgumentException("activity outcome must be terminal");
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
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 2_048) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
