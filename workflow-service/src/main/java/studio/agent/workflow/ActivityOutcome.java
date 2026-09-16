package studio.agent.workflow;

import java.util.Objects;
import studio.agent.contracts.TaskStatus;

/** Terminal result returned by the Agent worker activity boundary. */
public record ActivityOutcome(TaskStatus status, String artifactReference, String failureCode,
                              TaskApprovalRequest approval) {
  public ActivityOutcome(TaskStatus status, String artifactReference, String failureCode) {
    this(status, artifactReference, failureCode, null);
  }

  public ActivityOutcome {
    Objects.requireNonNull(status, "status is required");
    if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED
        && status != TaskStatus.WAITING_FOR_APPROVAL) {
      throw new IllegalArgumentException("activity outcome has an unsupported status");
    }
    if (status == TaskStatus.SUCCEEDED) {
      requireText(artifactReference, "artifactReference");
      if (failureCode != null || approval != null) throw new IllegalArgumentException("success cannot have failure or approval details");
    } else if (status == TaskStatus.FAILED) {
      requireText(failureCode, "failureCode");
      if (artifactReference != null || approval != null) throw new IllegalArgumentException("failure cannot have artifact or approval details");
    } else if (status == TaskStatus.CANCELED) {
      if (artifactReference != null || failureCode != null || approval != null) throw new IllegalArgumentException("canceled activity cannot have result details");
    } else {
      if (artifactReference != null || failureCode != null || approval == null) {
        throw new IllegalArgumentException("approval outcome must contain only an approval request");
      }
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }
}

/** Safe, typed human-confirmation payload returned by a worker when a marker is ambiguous. */
record TaskApprovalRequest(String type, String markerId, String reasonCode, String reference) {
  TaskApprovalRequest {
    if (type == null || type.isBlank() || markerId == null || markerId.isBlank()
        || reasonCode == null || reasonCode.isBlank() || reference == null || reference.isBlank()) {
      throw new IllegalArgumentException("approval request is incomplete");
    }
  }
}
