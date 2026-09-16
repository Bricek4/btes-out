package studio.agent.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import studio.agent.contracts.TaskStatus;

/**
 * A deterministic, signal-driven workflow. Network, database and AI work is delegated to one
 * retryable Activity; only opaque references and validated state are retained in workflow history.
 */
public final class TaskWorkflowImpl implements TaskWorkflow {
  private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);
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
  private CancellationScope activityScope;
  private ApprovalDecision approvalDecision;
  private TaskApprovalRequest approvalRequest;
  private String approvedReference;
  private String taskId;
  private WorkflowInput workflowInput;

  @Override
  public TaskWorkflowResult run(WorkflowInput input) {
    workflowInput = input;
    taskId = input.taskId();
    approvedReference = input.approvedReference();
    transition(TaskStatus.RUNNING);

    if (input.requiresApproval()) {
      TaskWorkflowResult approvalResult = awaitApproval();
      if (approvalResult != null) return approvalResult;
    }

    while (true) {
      if (pauseRequested) {
        transition(TaskStatus.PAUSED);
        Workflow.await(() -> cancelRequested || !pauseRequested);
        if (cancelRequested) return canceled("CANCELED_BY_USER");
        transition(TaskStatus.RUNNING);
      }
      if (cancelRequested) return canceled("CANCELED_BY_USER");

      ActivityInvocation invocation = invokeActivity(input.withApprovedReference(approvedReference));
      if (invocation.canceled() || cancelRequested) return canceled("CANCELED_BY_USER");
      if (invocation.failureCode() != null) return failed(invocation.failureCode());
      ActivityOutcome outcome = invocation.outcome();

      if (pauseRequested) {
        transition(TaskStatus.PAUSED);
        Workflow.await(() -> cancelRequested || !pauseRequested);
        if (cancelRequested) return canceled("CANCELED_BY_USER");
        transition(TaskStatus.RUNNING);
      }
      if (outcome == null) return failed("EMPTY_ACTIVITY_RESULT");
      if (outcome.status() == TaskStatus.WAITING_FOR_APPROVAL) {
        approvalRequest = outcome.approval();
        TaskWorkflowResult approvalResult = awaitApproval();
        if (approvalResult != null) return approvalResult;
        continue;
      }
      if (outcome.status() == TaskStatus.SUCCEEDED) {
        artifactReference = outcome.artifactReference();
        transition(TaskStatus.SUCCEEDED);
        return completed(new TaskWorkflowResult(taskId, TaskStatus.SUCCEEDED, artifactReference, null));
      }
      if (outcome.status() == TaskStatus.CANCELED) return canceled("AGENT_CANCELED");
      return failed(outcome.failureCode());
    }
  }

  @Override
  public void pause() {
    if (status == TaskStatus.RUNNING) {
      pauseRequested = true;
      if (!activityInFlight) transition(TaskStatus.PAUSED);
    } else if (status == TaskStatus.QUEUED || status == TaskStatus.WAITING_FOR_APPROVAL) {
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
    if (activityScope != null) activityScope.cancel("task canceled by user");
    if (status == TaskStatus.RUNNING || status == TaskStatus.PAUSED || status == TaskStatus.WAITING_FOR_APPROVAL
        || status == TaskStatus.QUEUED) transition(TaskStatus.CANCELED);
  }

  @Override
  public void approve(ApprovalDecision decision) {
    if (status != TaskStatus.WAITING_FOR_APPROVAL || decision == null || !decision.valid()
        || !decision.matches(approvalRequest)) return;
    approvalDecision = decision;
    if (decision.rejected()) {
      cancelRequested = true;
      transition(TaskStatus.CANCELED);
    }
  }

  @Override
  public TaskWorkflowState state() {
    return new TaskWorkflowState(status, artifactReference, failureCode, approvalPending,
        pauseRequested, cancelRequested, approvalRequest);
  }

  @Override
  public WorkflowInput startInput() {
    return workflowInput;
  }

  private TaskWorkflowResult awaitApproval() {
    approvalPending = true;
    transition(TaskStatus.WAITING_FOR_APPROVAL);
    if (!Workflow.await(APPROVAL_TIMEOUT, () -> cancelRequested || approvalDecision != null)) {
      approvalPending = false;
      return canceled("APPROVAL_TIMEOUT");
    }
    approvalPending = false;
    if (cancelRequested) return canceled("CANCELED_BY_USER");
    if (approvalDecision != null && approvalDecision.rejected()) return canceled("APPROVAL_REJECTED");
    if (approvalRequest != null && approvalDecision != null && approvalDecision.accepted()) {
      approvedReference = approvalRequest.reference();
    }
    approvalDecision = null;
    approvalRequest = null;
    transition(TaskStatus.RUNNING);
    return null;
  }

  private ActivityInvocation invokeActivity(WorkflowInput input) {
    ActivityOutcome[] outcomeHolder = new ActivityOutcome[1];
    RuntimeException[] failureHolder = new RuntimeException[1];
    activityInFlight = true;
    activityScope = Workflow.newCancellationScope(() -> {
      try {
        outcomeHolder[0] = activities.execute(input);
      } catch (RuntimeException failure) {
        failureHolder[0] = failure;
      }
    });
    try {
      activityScope.run();
    } catch (CanceledFailure canceled) {
      return new ActivityInvocation(null, "CANCELED_BY_USER", true);
    } finally {
      activityInFlight = false;
      activityScope = null;
    }
    if (failureHolder[0] instanceof CanceledFailure) return new ActivityInvocation(null, "CANCELED_BY_USER", true);
    if (failureHolder[0] instanceof ActivityFailure failure) {
      return new ActivityInvocation(null, activityFailureCode(failure), false);
    }
    if (failureHolder[0] != null) return new ActivityInvocation(null, "AGENT_ACTIVITY_FAILED", false);
    return new ActivityInvocation(outcomeHolder[0], null, false);
  }

  private TaskWorkflowResult canceled(String code) {
    failureCode = code == null || code.isBlank() ? "CANCELED" : code;
    if (status != TaskStatus.CANCELED && status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED) {
      transition(TaskStatus.CANCELED);
    }
    return completed(new TaskWorkflowResult(taskId, TaskStatus.CANCELED, null, failureCode));
  }

  private TaskWorkflowResult failed(String code) {
    failureCode = code == null || code.isBlank() ? "AGENT_ACTIVITY_FAILED" : code;
    if (status != TaskStatus.FAILED && status != TaskStatus.CANCELED && status != TaskStatus.SUCCEEDED) {
      transition(TaskStatus.FAILED);
    }
    return completed(new TaskWorkflowResult(taskId, TaskStatus.FAILED, null, failureCode));
  }

  private TaskWorkflowResult completed(TaskWorkflowResult result) {
    Workflow.await(() -> Workflow.isEveryHandlerFinished());
    return result;
  }

  private void transition(TaskStatus next) {
    if (status == next) return;
    status.transitionTo(next);
    status = next;
  }

  private static String activityFailureCode(ActivityFailure failure) {
    Throwable cause = failure;
    while (cause != null) {
      if (cause instanceof ApplicationFailure application) {
        String type = application.getType();
        if (type != null && !type.isBlank()) return type;
      }
      cause = cause.getCause();
    }
    return "AGENT_ACTIVITY_FAILED";
  }

  private record ActivityInvocation(ActivityOutcome outcome, String failureCode, boolean canceled) { }
}
