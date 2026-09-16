package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;

class WorkflowHistoryPayloadValidationTest {
  @Test
  void activityOutcomesRejectRawContentThatWouldEnterTemporalHistory() {
    assertThatThrownBy(() -> new ActivityOutcome(TaskStatus.SUCCEEDED,
        "generated document body", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActivityOutcome(TaskStatus.FAILED, null,
        "provider said: full raw response"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaskApprovalRequest("SCREENSHOT_ROUTE_AMBIGUITY", "users",
        "ROUTE_EVIDENCE_INSUFFICIENT", "paste the source here"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaskApprovalRequest("raw explanation", "users",
        "ROUTE_EVIDENCE_INSUFFICIENT", "approval://screenshot-route/users/sha256-abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalResultsAcceptOnlyOpaqueArtifactsAndMachineFailureCodes() {
    assertThatThrownBy(() -> new TaskWorkflowResult("task-1", TaskStatus.SUCCEEDED,
        "raw markdown", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaskWorkflowResult("task-1", TaskStatus.FAILED,
        null, "stack trace with credentials")).isInstanceOf(IllegalArgumentException.class);
  }
}
