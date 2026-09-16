package studio.agent.workflow;

import java.util.Objects;
import studio.agent.contracts.TaskType;

/**
 * Deterministic workflow input. It contains only opaque references and control flags;
 * source text, credentials and model transcripts never enter Temporal history.
 */
public record WorkflowInput(
    String taskId,
    String projectId,
    TaskType type,
    String sourceReference,
    String templateVersionReference,
    String parametersReference,
    String providerProfileReference,
    boolean requiresApproval,
    String approvedReference) {

  public WorkflowInput(String taskId, String projectId, TaskType type, String sourceReference,
      String templateVersionReference, String parametersReference, String providerProfileReference,
      boolean requiresApproval) {
    this(taskId, projectId, type, sourceReference, templateVersionReference, parametersReference,
        providerProfileReference, requiresApproval, null);
  }

  public WorkflowInput {
    requirePathSegment(taskId, "taskId");
    requireText(projectId, "projectId");
    Objects.requireNonNull(type, "type is required");
    requireText(sourceReference, "sourceReference");
    requireText(templateVersionReference, "templateVersionReference");
    requireText(parametersReference, "parametersReference");
    requireText(providerProfileReference, "providerProfileReference");
    if (approvedReference != null && (approvedReference.isBlank() || approvedReference.length() > 2_048
        || !approvedReference.startsWith("approval://screenshot-route/"))) {
      throw new IllegalArgumentException("approvedReference is invalid");
    }
  }

  public WorkflowInput withApprovedReference(String reference) {
    return new WorkflowInput(taskId, projectId, type, sourceReference, templateVersionReference,
        parametersReference, providerProfileReference, requiresApproval, reference);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }

  private static void requirePathSegment(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
      throw new IllegalArgumentException(field + " must be a safe identifier");
    }
  }
}
