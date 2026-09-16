package studio.agent.workflow;

/** A small approval command; only an optional opaque evidence reference enters workflow history. */
public record ApprovalDecision(String decision, String approvedReference) {
  public ApprovalDecision {
    if (decision != null && decision.length() > 16) {
      throw new IllegalArgumentException("decision is invalid");
    }
    if (approvedReference != null) {
      WorkflowInput.requireOpaqueReference(approvedReference,
          "approval://screenshot-route/", "approvedReference");
    }
  }

  boolean accepted() {
    return decision != null && switch (decision.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "approve", "approved", "continue" -> true;
      default -> false;
    };
  }

  boolean rejected() {
    return decision != null && switch (decision.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "reject", "rejected", "cancel" -> true;
      default -> false;
    };
  }

  boolean valid() {
    return accepted() || rejected();
  }

  boolean matches(TaskApprovalRequest pending) {
    if (rejected()) return approvedReference == null;
    if (!accepted()) return false;
    return pending == null ? approvedReference == null : pending.reference().equals(approvedReference);
  }
}
