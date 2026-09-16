package studio.agent.platform.task;

import studio.agent.contracts.TaskStatus;

public final class TaskRules {
  private TaskRules() {}
  public static boolean canDelete(TaskStatus status) { return status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED || status == TaskStatus.CANCELED; }
  public static TaskStatus apply(TaskStatus status, TaskAction action) {
    return switch (action) {
      case PAUSE -> status == TaskStatus.PAUSED ? status : status.transitionTo(TaskStatus.PAUSED);
      case RESUME -> status == TaskStatus.RUNNING ? status : status.transitionTo(TaskStatus.RUNNING);
      case CANCEL -> status == TaskStatus.CANCELED ? status : status.transitionTo(TaskStatus.CANCELED);
    };
  }
}
