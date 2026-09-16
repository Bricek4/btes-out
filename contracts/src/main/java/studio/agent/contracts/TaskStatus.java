package studio.agent.contracts;

import java.util.EnumSet;
import java.util.Set;

public enum TaskStatus {
  QUEUED, RUNNING, PAUSED, WAITING_FOR_APPROVAL, SUCCEEDED, FAILED, CANCELED;

  public TaskStatus transitionTo(TaskStatus target) {
    if (!allowedTargets().contains(target)) {
      throw new IllegalStateException("Task cannot transition from " + this + " to " + target);
    }
    return target;
  }

  private Set<TaskStatus> allowedTargets() {
    return switch (this) {
      case QUEUED -> EnumSet.of(RUNNING, CANCELED);
      case RUNNING -> EnumSet.of(PAUSED, WAITING_FOR_APPROVAL, SUCCEEDED, FAILED, CANCELED);
      case PAUSED -> EnumSet.of(RUNNING, CANCELED);
      case WAITING_FOR_APPROVAL -> EnumSet.of(RUNNING, CANCELED);
      case SUCCEEDED, FAILED, CANCELED -> EnumSet.noneOf(TaskStatus.class);
    };
  }
}
