package studio.agent.platform.task;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;

class TaskRulesTest {
  @Test
  void only_terminal_tasks_can_be_deleted() {
    assertTrue(TaskRules.canDelete(TaskStatus.SUCCEEDED));
    assertTrue(TaskRules.canDelete(TaskStatus.FAILED));
    assertTrue(TaskRules.canDelete(TaskStatus.CANCELED));
    assertFalse(TaskRules.canDelete(TaskStatus.RUNNING));
    assertFalse(TaskRules.canDelete(TaskStatus.WAITING_FOR_APPROVAL));
  }

  @Test
  void actions_are_idempotent_but_illegal_transitions_are_rejected() {
    assertEquals(TaskStatus.PAUSED, TaskRules.apply(TaskStatus.RUNNING, TaskAction.PAUSE));
    assertEquals(TaskStatus.PAUSED, TaskRules.apply(TaskStatus.PAUSED, TaskAction.PAUSE));
    assertEquals(TaskStatus.RUNNING, TaskRules.apply(TaskStatus.PAUSED, TaskAction.RESUME));
    assertEquals(TaskStatus.CANCELED, TaskRules.apply(TaskStatus.CANCELED, TaskAction.CANCEL));
    assertThrows(IllegalStateException.class, () -> TaskRules.apply(TaskStatus.SUCCEEDED, TaskAction.PAUSE));
  }
}
