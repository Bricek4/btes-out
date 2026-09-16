package studio.agent.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

class TaskContractTest {
  @Test
  void accepts_a_public_task_request_with_immutable_template_version_and_bounded_parameters() {
    var templateVersionId = UUID.randomUUID();
    var request = new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, templateVersionId,
        Map.of("audience", TextNode.valueOf("maintainers")));

    assertEquals(TaskStatus.QUEUED, request.initialStatus());
    assertEquals(templateVersionId, request.templateVersionId());
    assertEquals("maintainers", request.parameters().get("audience").asText());
  }

  @Test
  void task_request_deep_copies_parameters_on_input_and_access() {
    var original = JsonNodeFactory.instance.objectNode().put("title", "before");
    var request = new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, UUID.randomUUID(),
        Map.of("context", original));
    original.put("title", "caller changed it");

    assertEquals("before", request.parameters().get("context").get("title").asText());
    ((ObjectNode) request.parameters().get("context")).put("title", "accessor changed it");
    assertEquals("before", request.parameters().get("context").get("title").asText());
  }

  @Test
  void exposes_only_the_allowed_public_and_worker_record_components() {
    assertEquals(List.of("projectId", "type", "templateVersionId", "parameters"),
        java.util.Arrays.stream(CreateTaskRequest.class.getRecordComponents()).map(component -> component.getName()).toList());
    assertEquals(List.of("taskId", "projectId", "type", "sourceReference", "templateVersionReference",
            "parametersReference", "providerProfileReference"),
        java.util.Arrays.stream(WorkerTaskRequest.class.getRecordComponents()).map(component -> component.getName()).toList());
  }

  @Test
  void keeps_idempotency_out_of_the_public_request_and_validates_the_internal_command() {
    var request = new CreateTaskRequest(UUID.randomUUID(), TaskType.USER_GUIDE, UUID.randomUUID(), Map.of());
    var command = new CreateTaskCommand(request, "dedupe-123");

    assertEquals("dedupe-123", command.idempotencyKey());
    assertTrue(java.util.Arrays.stream(CreateTaskRequest.class.getRecordComponents())
        .noneMatch(component -> component.getName().equals("idempotencyKey")));
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskCommand(request, " "));
  }

  @Test
  void rejects_idempotency_keys_longer_than_255_characters() {
    var request = new CreateTaskRequest(UUID.randomUUID(), TaskType.HTML, UUID.randomUUID(), Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskCommand(request, "a".repeat(256)));
    assertEquals(255, new CreateTaskCommand(request, "a".repeat(255)).idempotencyKey().length());
  }

  @Test
  void rejects_missing_required_task_request_inputs() {
    var projectId = UUID.randomUUID();
    assertThrows(NullPointerException.class,
        () -> new CreateTaskRequest(null, TaskType.PROJECT_DOCS, UUID.randomUUID(), Map.of()));
    assertThrows(NullPointerException.class,
        () -> new CreateTaskRequest(projectId, null, UUID.randomUUID(), Map.of()));
    assertThrows(NullPointerException.class,
        () -> new CreateTaskRequest(projectId, TaskType.PROJECT_DOCS, null, Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(projectId, TaskType.PROJECT_DOCS, UUID.randomUUID(), null));
  }

  @Test
  void rejects_parameters_above_the_property_or_serialized_size_limit() {
    var parameters = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    for (int index = 0; index < 100; index++) parameters.put("field" + index, TextNode.valueOf("value"));
    assertEquals(100, new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, UUID.randomUUID(), parameters).parameters().size());
    parameters.put("field100", TextNode.valueOf("value"));
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, UUID.randomUUID(), parameters));
    assertEquals(65_536, new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, UUID.randomUUID(),
        Map.of("body", TextNode.valueOf("x".repeat(65_525)))).parametersSerializedSize());
    assertThrows(IllegalArgumentException.class,
        () -> new CreateTaskRequest(UUID.randomUUID(), TaskType.PROJECT_DOCS, UUID.randomUUID(),
            Map.of("body", TextNode.valueOf("x".repeat(65_526)))));
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
    var request = new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, "source://app",
        "template-version://v1", "parameters://task-input", "provider-profile://default");

    assertEquals(taskId, request.taskId());
    assertEquals(projectId, request.projectId());
    assertEquals("template-version://v1", request.templateVersionReference());
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(null, projectId, TaskType.SCREENSHOT, "source://app", "template://v1", "parameters://task", "provider://default"));
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(taskId, null, TaskType.SCREENSHOT, "source://app", "template://v1", "parameters://task", "provider://default"));
    assertThrows(NullPointerException.class, () -> new WorkerTaskRequest(taskId, projectId, null, "source://app", "template://v1", "parameters://task", "provider://default"));
    assertThrows(IllegalArgumentException.class, () -> new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, " ", "template://v1", "parameters://task", "provider://default"));
    assertThrows(IllegalArgumentException.class, () -> new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, "source://app", " ", "parameters://task", "provider://default"));
    assertThrows(IllegalArgumentException.class, () -> new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, "source://app", "template://v1", " ", "provider://default"));
    assertThrows(IllegalArgumentException.class, () -> new WorkerTaskRequest(taskId, projectId, TaskType.SCREENSHOT, "source://app", "template://v1", "parameters://task", " "));
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
    var snapshot = new TaskSnapshot(UUID.randomUUID(), UUID.randomUUID(), TaskType.PROJECT_DOCS,
        TaskStatus.QUEUED, Instant.now(), Instant.now(), null, null);

    assertTrue(java.util.Arrays.stream(TaskSnapshot.class.getRecordComponents())
        .noneMatch(component -> component.getName().equals("idempotencyKey")));
    assertEquals(TaskStatus.QUEUED, snapshot.status());
  }
}
