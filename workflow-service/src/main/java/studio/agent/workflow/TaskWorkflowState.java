package studio.agent.workflow;

import studio.agent.contracts.TaskStatus;

/** Query view of the durable Temporal workflow state. */
public record TaskWorkflowState(TaskStatus status, String artifactReference, String failureCode,
                                boolean approvalPending, boolean pauseRequested, boolean cancelRequested) {
}
