package studio.agent.workflow;

import java.util.Objects;
import studio.agent.contracts.TaskStatus;

/** Terminal result returned by the Agent worker activity boundary. */
public record ActivityOutcome(TaskStatus status, String artifactReference, String failureCode) {
  public ActivityOutcome {
    Objects.requireNonNull(status, "status is required");
    if (status != TaskStatus.SUCCEEDED && status != TaskStatus.FAILED && status != TaskStatus.CANCELED) {
      throw new IllegalArgumentException("activity outcome must be terminal");
    }
    if (status == TaskStatus.SUCCEEDED) {
      requireText(artifactReference, "artifactReference");
      if (failureCode != null) throw new IllegalArgumentException("success cannot have a failure code");
    } else if (status == TaskStatus.FAILED) {
      requireText(failureCode, "failureCode");
      if (artifactReference != null) throw new IllegalArgumentException("failure cannot have an artifact reference");
    } else if (artifactReference != null || failureCode != null) {
      throw new IllegalArgumentException("canceled activity cannot have result details");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }
}
