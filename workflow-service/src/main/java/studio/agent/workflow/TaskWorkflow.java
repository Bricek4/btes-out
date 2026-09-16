package studio.agent.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Durable task lifecycle owned by Temporal. */
@WorkflowInterface
public interface TaskWorkflow {
  @WorkflowMethod
  TaskWorkflowResult run(WorkflowInput input);

  @SignalMethod
  void pause();

  @SignalMethod
  void resume();

  @SignalMethod
  void cancel();

  @SignalMethod
  void approve(ApprovalDecision decision);

  @QueryMethod
  TaskWorkflowState state();

  /** Used only by the internal bridge to make repeated starts idempotent and conflict-safe. */
  @QueryMethod
  WorkflowInput startInput();
}
