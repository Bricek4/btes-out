package studio.agent.worker;

import java.util.Objects;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;
import studio.agent.contracts.WorkerTaskRequest;
import studio.agent.contracts.WorkerTaskResult;

/** Executes only validated opaque worker references; source contents and provider credentials are never logged or retained. */
@org.springframework.stereotype.Service
public final class WorkerExecutionService {
  public LocalWorkerResult execute(WorkerTaskRequest request) {
    Objects.requireNonNull(request, "request is required");
    if (request.type() == TaskType.SCREENSHOT) {
      return new LocalWorkerResult(null, null, "proposal://screenshots/" + request.taskId());
    }
    var failure = new WorkerTaskResult(request.taskId(), TaskStatus.FAILED, null,
        "AGENT_EXECUTION_PIPELINE_UNAVAILABLE");
    return new LocalWorkerResult(failure, null, null);
  }
  public record LocalWorkerResult(WorkerTaskResult completion, String artifactReference, String approvalReference) { }
}
