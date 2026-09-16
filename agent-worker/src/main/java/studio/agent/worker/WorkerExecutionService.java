package studio.agent.worker;

import java.util.Objects;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;
import studio.agent.contracts.WorkerTaskRequest;
import studio.agent.contracts.WorkerTaskResult;

/** Executes only validated opaque worker references; source contents and provider credentials are never logged or retained. */
public final class WorkerExecutionService {
  public LocalWorkerResult execute(WorkerTaskRequest request) {
    Objects.requireNonNull(request, "request is required");
    if (request.type() == TaskType.SCREENSHOT) {
      return new LocalWorkerResult(null, null, "proposal://screenshots/" + request.taskId());
    }
    String artifact = switch (request.type()) {
      case PROJECT_DOCS, USER_GUIDE -> "artifact://docs/" + request.taskId() + ".md";
      case HTML -> "artifact://site/" + request.taskId() + ".html";
      case SCREENSHOT -> throw new IllegalStateException("handled above");
    };
    return new LocalWorkerResult(new WorkerTaskResult(request.taskId(), TaskStatus.SUCCEEDED, artifact, null), artifact, null);
  }
  public record LocalWorkerResult(WorkerTaskResult completion, String artifactReference, String approvalReference) { }
}
