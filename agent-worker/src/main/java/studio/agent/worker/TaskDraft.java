package studio.agent.worker;

import java.util.Map;
import java.util.Objects;
import studio.agent.contracts.TaskType;

public record TaskDraft(TaskType workflowType, Map<String, String> parameters, String summary, String taskReference) {
  public TaskDraft {
    Objects.requireNonNull(workflowType, "workflowType is required");
    parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters are required"));
    if (parameters.isEmpty() || summary == null || summary.isBlank()) throw new IllegalArgumentException("draft must be complete");
    if (taskReference != null) throw new IllegalArgumentException("an editable draft cannot reference a launched task");
  }
}
