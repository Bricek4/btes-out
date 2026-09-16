package studio.agent.workflow;

/** A small, validated approval message; free-form chat and model output are not stored here. */
public record ApprovalDecision(String decision, String text) {
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
}
