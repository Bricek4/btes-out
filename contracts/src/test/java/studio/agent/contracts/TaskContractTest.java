package studio.agent.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskContractTest {
  @Test
  void accepts_a_valid_idempotent_task_request() {
    var request = new CreateTaskRequest(UUID.randomUUID(), TaskType.DOCUMENTATION, "dedupe-123", "source://project-42");

    assertEquals(TaskStatus.QUEUED, request.initialStatus());
    assertEquals("dedupe-123", request.idempotencyKey());
  }

  @Test
  void rejects_blank_idempotency_key() {
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(UUID.randomUUID(), TaskType.DOCUMENTATION, " ", "source://project-42"));
  }

  @Test
  void rejects_idempotency_keys_longer_than_255_characters() {
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(UUID.randomUUID(), TaskType.DOCUMENTATION, "a".repeat(256), "source://project-42"));
    assertEquals(255, new CreateTaskRequest(UUID.randomUUID(), TaskType.DOCUMENTATION,
        "a".repeat(255), "source://project-42").idempotencyKey().length());
  }

  @Test
  void rejects_missing_required_task_request_inputs() {
    var projectId = UUID.randomUUID();
    assertThrows(NullPointerException.class,
        () -> new CreateTaskRequest(null, TaskType.DOCUMENTATION, "key", "source://project"));
    assertThrows(NullPointerException.class,
        () -> new CreateTaskRequest(projectId, null, "key", "source://project"));
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(projectId, TaskType.DOCUMENTATION, "key", " "));
  }

  @Test
  void enforces_the_complete_task_status_transition_matrix() {
    Map<TaskStatus, EnumSet<TaskStatus>> allowed = Map.of(
        TaskStatus.QUEUED, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELED),
        TaskStatus.RUNNING, EnumSet.of(TaskStatus.WAITING_FOR_APPROVAL, TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELED),
        TaskStatus.WAITING_FOR_APPROVAL, EnumSet.of(TaskStatus.QUEUED, TaskStatus.SUCCEEDED, TaskStatus.CANCELED),
        TaskStatus.SUCCEEDED, EnumSet.noneOf(TaskStatus.class),
        TaskStatus.FAILED, EnumSet.noneOf(TaskStatus.class),
        TaskStatus.CANCELED, EnumSet.noneOf(TaskStatus.class));

    for (var source : TaskStatus.values()) {
      for (var target : TaskStatus.values()) {
        if (allowed.get(source).contains(target)) {
          assertEquals(target, source.transitionTo(target), source + " -> " + target);
        } else {
          assertThrows(IllegalStateException.class, () -> source.transitionTo(target), source + " -> " + target);
        }
      }
    }
  }

  @Test
  void worker_request_validates_required_references_and_preserves_task_correlation() {
    var taskId = UUID.randomUUID();
    var projectId = UUID.randomUUID();
    var request = new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, "source://app");

    assertEquals(taskId, request.taskId());
    assertEquals(projectId, request.projectId());
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(null, projectId, TaskType.SCREENSHOT, "source://app"));
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(taskId, null, TaskType.SCREENSHOT, "source://app"));
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(taskId, projectId, null, "source://app"));
    assertThrows(IllegalArgumentException.class, () -> new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, " "));
  }

  @Test
  void worker_results_are_terminal_correlated_and_non_contradictory() {
    var taskId = UUID.randomUUID();
    var succeeded = new WorkerTaskResult(taskId, TaskStatus.SUCCEEDED, "artifact://docs/readme", null);

    assertEquals(taskId, succeeded.taskId());
    assertEquals(TaskStatus.SUCCEEDED, succeeded.status());
    assertThrows(IllegalArgumentException.class,
        () -> new WorkerTaskResult(taskId, TaskStatus.RUNNING, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new WorkerTaskResult(taskId, TaskStatus.SUCCEEDED, "artifact://docs/readme", "UNEXPECTED"));
    assertThrows(IllegalArgumentException.class,
        () -> new WorkerTaskResult(taskId, TaskStatus.FAILED, "artifact://docs/readme", "WORK_FAILED"));
    assertThrows(IllegalArgumentException.class,
        () -> new WorkerTaskResult(taskId, TaskStatus.CANCELED, "artifact://docs/readme", null));
    assertThrows(IllegalArgumentException.class,
        () -> new WorkerTaskResult(taskId, TaskStatus.CANCELED, null, "WORK_FAILED"));
  }

  @Test
  void public_task_snapshot_does_not_contain_an_idempotency_key() {
    var snapshot = new TaskSnapshot(UUID.randomUUID(), UUID.randomUUID(), TaskType.DOCUMENTATION,
        TaskStatus.QUEUED, Instant.now(), Instant.now(), null, null);

    assertTrue(java.util.Arrays.stream(TaskSnapshot.class.getRecordComponents())
        .noneMatch(component -> component.getName().equals("idempotencyKey")));
    assertEquals(TaskStatus.QUEUED, snapshot.status());
  }
}
