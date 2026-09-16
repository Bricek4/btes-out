package studio.agent.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import studio.agent.contracts.TaskStatus;

/**
 * A deterministic, signal-driven workflow. Network, database and AI work is delegated to one
 * retryable Activity; only opaque references and validated state are retained in workflow history.
 */
public final class TaskWorkflowImpl implements TaskWorkflow {
  private final TaskActivities activities = Workflow.newActivityStub(TaskActivities.class,
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofMinutes(15))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
          .build());

  private TaskStatus status = TaskStatus.QUEUED;
  private String artifactReference;
  private String failureCode;
  private boolean approvalPending;
  private boolean pauseRequested;
  private boolean cancelRequested;
  private boolean activityInFlight;
  private ApprovalDecision approvalDecision;
  private String taskId;

  @Override
  public TaskWorkflowResult run(WorkflowInput input) {
    taskId = input.taskId();
    transition(TaskStatus.RUNNING);

    if (input.requiresApproval()) {
      approvalPending = true;
      transition(TaskStatus.WAITING_FOR_APPROVAL);
      Workflow.await(() -> cancelRequested || approvalDecision != null);
      approvalPending = false;
      if (cancelRequested) return canceled("CANCELED_BY_USER");
      if (approvalDecision.rejected()) return canceled("APPROVAL_REJECTED");
      transition(TaskStatus.RUNNING);
    }

    if (pauseRequested) {
      transition(TaskStatus.PAUSED);
      Workflow.await(() -> cancelRequested || !pauseRequested);
      if (cancelRequested) return canceled("CANCELED_BY_USER");
      transition(TaskStatus.RUNNING);
    }
    if (cancelRequested) return canceled("CANCELED_BY_USER");

    ActivityOutcome outcome;
    activityInFlight = true;
    try {
      outcome = activities.execute(input);
    } catch (ActivityFailure failure) {
      activityInFlight = false;
      return failed("AGENT_ACTIVITY_FAILED");
    } catch (RuntimeException failure) {
      activityInFlight = false;
      return failed("AGENT_ACTIVITY_FAILED");
    }
    activityInFlight = false;

    if (cancelRequested) return canceled("CANCELED_BY_USER");
    if (pauseRequested) {
      transition(TaskStatus.PAUSED);
      Workflow.await(() -> cancelRequested || !pauseRequested);
      if (cancelRequested) return canceled("CANCELED_BY_USER");
      transition(TaskStatus.RUNNING);
    }
    if (outcome == null) return failed("EMPTY_ACTIVITY_RESULT");
    if (outcome.status() == TaskStatus.SUCCEEDED) {
      artifactReference = outcome.artifactReference();
      transition(TaskStatus.SUCCEEDED);
      return new TaskWorkflowResult(taskId, TaskStatus.SUCCEEDED, artifactReference, null);
    }
    if (outcome.status() == TaskStatus.CANCELED) return canceled("AGENT_CANCELED");
    return failed(outcome.failureCode());
  }

  @Override
  public void pause() {
    if (status == TaskStatus.RUNNING) {
      pauseRequested = true;
      if (!activityInFlight) transition(TaskStatus.PAUSED);
    } else if (status == TaskStatus.QUEUED) {
      pauseRequested = true;
    }
  }

  @Override
  public void resume() {
    pauseRequested = false;
    if (status == TaskStatus.PAUSED) transition(TaskStatus.RUNNING);
  }

  @Override
  public void cancel() {
    if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED || status == TaskStatus.CANCELED) return;
    cancelRequested = true;
    if (status == TaskStatus.RUNNING || status == TaskStatus.PAUSED || status == TaskStatus.WAITING_FOR_APPROVAL
        || status == TaskStatus.QUEUED) transition(TaskStatus.CANCELED);
  }

  @Override
  public void approve(ApprovalDecision decision) {
    if (status != TaskStatus.WAITING_FOR_APPROVAL || decision == null || !decision.valid()) return;
    approvalDecision = decision;
    if (decision.rejected()) {
      cancelRequested = true;
      transition(TaskStatus.CANCELED);
    }
  }

  @Override
  public TaskWorkflowState state() {
    return new TaskWorkflowState(status, artifactReference, failureCode, approvalPending,
        pauseRequested, cancelRequested);
  }

  private TaskWorkflowResult canceled(String code) {
    failureCode = code == null || code.isBlank() ? "CANCELED" : code;
    if (status != TaskStatus.CANCELED && status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED) {
      transition(TaskStatus.CANCELED);
    }
    return new TaskWorkflowResult(taskId, TaskStatus.CANCELED, null, failureCode);
  }

  private TaskWorkflowResult failed(String code) {
    failureCode = code == null || code.isBlank() ? "AGENT_ACTIVITY_FAILED" : code;
    if (status != TaskStatus.FAILED && status != TaskStatus.CANCELED && status != TaskStatus.SUCCEEDED) {
      transition(TaskStatus.FAILED);
    }
    return new TaskWorkflowResult(taskId, TaskStatus.FAILED, null, failureCode);
  }

  private void transition(TaskStatus next) {
    if (status == next) return;
    status.transitionTo(next);
    status = next;
  }
}
