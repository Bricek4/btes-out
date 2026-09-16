package studio.agent.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.util.Objects;

final class TemporalWorkflowTaskGateway implements WorkflowTaskGateway {
  private final WorkflowClient client;
  private final String taskQueue;

  TemporalWorkflowTaskGateway(WorkflowClient client, String taskQueue) {
    this.client = Objects.requireNonNull(client, "workflow client is required");
    if (taskQueue == null || taskQueue.isBlank()) throw new IllegalArgumentException("task queue is required");
    this.taskQueue = taskQueue;
  }

  @Override
  public WorkflowStartResult start(WorkflowInput input) {
    Objects.requireNonNull(input, "workflow input is required");
    String workflowId = workflowId(input.taskId());
    TaskWorkflow workflow = client.newWorkflowStub(TaskWorkflow.class,
        WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build());
    try {
      WorkflowClient.start(workflow::run, input);
      return new WorkflowStartResult(input.taskId(), workflowId, true);
    } catch (WorkflowExecutionAlreadyStarted exists) {
      TaskWorkflow existing = existing(input.taskId());
      WorkflowInput original = temporalCall(existing::startInput);
      if (!input.equals(original)) throw new WorkflowStartConflictException();
      return new WorkflowStartResult(input.taskId(), workflowId, false);
    } catch (WorkflowServiceException unavailable) {
      throw new WorkflowBridgeUnavailableException();
    }
  }

  @Override
  public TaskWorkflowState query(String taskId) {
    validateTaskId(taskId);
    return temporalCall(existing(taskId)::state);
  }

  @Override public void pause(String taskId) { signal(taskId, TaskWorkflow::pause); }
  @Override public void resume(String taskId) { signal(taskId, TaskWorkflow::resume); }
  @Override public void cancel(String taskId) { signal(taskId, TaskWorkflow::cancel); }

  @Override
  public void approve(String taskId, ApprovalDecision decision) {
    if (decision == null || !decision.valid()) throw new IllegalArgumentException("decision is invalid");
    TaskWorkflow workflow = existing(taskId);
    TaskWorkflowState state = temporalCall(workflow::state);
    if (state.status() != studio.agent.contracts.TaskStatus.WAITING_FOR_APPROVAL
        || !decision.matches(state.approvalRequest())) {
      throw new IllegalArgumentException("approval does not match pending evidence");
    }
    try {
      workflow.approve(decision);
    } catch (WorkflowNotFoundException missing) {
      throw new WorkflowTaskNotFoundException();
    } catch (WorkflowServiceException unavailable) {
      throw new WorkflowBridgeUnavailableException();
    }
  }

  private void signal(String taskId, WorkflowSignal signal) {
    validateTaskId(taskId);
    try {
      signal.send(existing(taskId));
    } catch (WorkflowNotFoundException missing) {
      throw new WorkflowTaskNotFoundException();
    } catch (WorkflowServiceException unavailable) {
      throw new WorkflowBridgeUnavailableException();
    }
  }

  private <T> T temporalCall(TemporalQuery<T> query) {
    try {
      return query.execute();
    } catch (WorkflowNotFoundException missing) {
      throw new WorkflowTaskNotFoundException();
    } catch (WorkflowServiceException unavailable) {
      throw new WorkflowBridgeUnavailableException();
    }
  }

  private TaskWorkflow existing(String taskId) {
    return client.newWorkflowStub(TaskWorkflow.class, workflowId(taskId));
  }

  private static String workflowId(String taskId) {
    validateTaskId(taskId);
    return "task-" + taskId;
  }

  private static void validateTaskId(String taskId) {
    WorkflowInput.requirePathSegment(taskId, "taskId");
  }

  @FunctionalInterface private interface WorkflowSignal { void send(TaskWorkflow workflow); }
  @FunctionalInterface private interface TemporalQuery<T> { T execute(); }
}

final class WorkflowTaskNotFoundException extends RuntimeException { }
final class WorkflowStartConflictException extends RuntimeException { }
final class WorkflowBridgeUnavailableException extends RuntimeException { }
