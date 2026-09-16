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
    requirePathSegment(projectId, "projectId");
    Objects.requireNonNull(type, "type is required");
    requireOpaqueReference(sourceReference, "source://", "sourceReference");
    requireOpaqueReference(templateVersionReference, "template://", "templateVersionReference");
    requireOpaqueReference(parametersReference, "parameters://", "parametersReference");
    requireOpaqueReference(providerProfileReference, "provider://", "providerProfileReference");
    if (approvedReference != null) requireOpaqueReference(approvedReference,
        "approval://screenshot-route/", "approvedReference");
  }

  public WorkflowInput withApprovedReference(String reference) {
    return new WorkflowInput(taskId, projectId, type, sourceReference, templateVersionReference,
        parametersReference, providerProfileReference, requiresApproval, reference);
  }

  static void requireOpaqueReference(String value, String prefix, String field) {
    if (value == null || value.length() > 2_048 || !value.startsWith(prefix)
        || !value.matches("[A-Za-z][A-Za-z0-9+.-]*://[A-Za-z0-9][A-Za-z0-9._~:/-]*")) {
      throw new IllegalArgumentException(field + " must be an opaque reference");
    }
  }

  static void requireMachineCode(String value, String field) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException(field + " must be a machine code");
    }
  }

  static void requirePathSegment(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
      throw new IllegalArgumentException(field + " must be a safe identifier");
    }
  }
}
