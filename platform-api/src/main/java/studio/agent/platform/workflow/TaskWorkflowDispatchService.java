package studio.agent.platform.workflow;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import studio.agent.contracts.TaskType;
import studio.agent.platform.config.PlatformProperties;
import studio.agent.platform.config.RequiredPlatformSettings;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable hand-off from Platform's committed task row to Temporal. The outbox row is written in
 * the same transaction as task creation; a short-lived HTTP failure therefore cannot lose a task.
 * The dispatcher only sends opaque references and retries with bounded exponential backoff.
 */
@Service
public class TaskWorkflowDispatchService {
  private static final int BATCH_SIZE = 8;
  private static final int MAX_ERROR_LENGTH = 120;

  private final JdbcClient jdbc;
  private final RestClient workflow;
  private final String token;

  public TaskWorkflowDispatchService(JdbcClient jdbc, PlatformProperties properties) {
    this.jdbc = jdbc;
    String baseUrl = RequiredPlatformSettings.require("WORKFLOW_SERVICE_BASE_URL", properties.workflowServiceBaseUrl());
    this.token = RequiredPlatformSettings.require("WORKFLOW_SERVICE_TOKEN", properties.workflowServiceToken());
    this.workflow = RestClient.builder().baseUrl(baseUrl).build();
  }

  public void enqueue(UUID taskId, UUID projectId, TaskType type, UUID revisionId, UUID templateVersionId,
      UUID providerProfileId, boolean requiresApproval) {
    if (taskId == null || projectId == null || type == null || revisionId == null
        || templateVersionId == null || providerProfileId == null) {
      throw new IllegalArgumentException("workflow dispatch input is incomplete");
    }
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.sql("""
        INSERT INTO task_workflow_dispatch(
          task_id,workflow_id,project_id,task_type,source_reference,template_version_reference,
          parameters_reference,provider_profile_reference,requires_approval,attempts,next_attempt_at,created_at)
        VALUES(:task,:workflow,:project,:type,:source,:template,:parameters,:provider,:approval,0,:next,:created)
        ON CONFLICT (task_id) DO NOTHING
        """)
        .param("task", taskId)
        .param("workflow", workflowId(taskId))
        .param("project", projectId)
        .param("type", type.name())
        .param("source", "source://revision/" + revisionId)
        .param("template", "template://version/" + templateVersionId)
        .param("parameters", "parameters://task/" + taskId)
        .param("provider", "provider://profile/" + providerProfileId)
        .param("approval", requiresApproval)
        .param("next", now)
        .param("created", now)
        .update();
  }

  /** Runs on a single platform instance in local Compose; SKIP LOCKED keeps it safe to scale later. */
  @Scheduled(fixedDelayString = "${agent-studio.workflow.dispatch-interval-ms:1000}")
  public void dispatchPending() {
    for (int i = 0; i < BATCH_SIZE; i++) {
      DispatchRow row = claimOne();
      if (row == null) return;
      try {
        workflow.post()
            .uri("/internal/workflows/tasks/{taskId}/start", row.taskId())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .body(Map.of(
                "projectId", row.projectId().toString(),
                "type", row.taskType(),
                "sourceReference", row.sourceReference(),
                "templateVersionReference", row.templateVersionReference(),
                "parametersReference", row.parametersReference(),
                "providerProfileReference", row.providerProfileReference(),
                "requiresApproval", row.requiresApproval()))
            .retrieve()
            .toBodilessEntity();
        markDispatched(row.taskId());
      } catch (RestClientResponseException failure) {
        // A deterministic duplicate is safe: the bridge compares the original opaque input.
        if (failure.getStatusCode().value() == 409) {
          markDispatched(row.taskId());
        } else {
          markFailure(row, "WORKFLOW_HTTP_" + failure.getStatusCode().value());
        }
      } catch (RuntimeException failure) {
        markFailure(row, "WORKFLOW_UNAVAILABLE");
      }
    }
  }

  private DispatchRow claimOne() {
    OffsetDateTime now = OffsetDateTime.now();
    return jdbc.sql("""
        UPDATE task_workflow_dispatch d
           SET attempts=d.attempts+1,
               next_attempt_at=:retry
         WHERE d.task_id=(
           SELECT candidate.task_id FROM task_workflow_dispatch candidate
            WHERE candidate.dispatched_at IS NULL AND candidate.next_attempt_at<=:now
            ORDER BY candidate.created_at
            FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING d.task_id,d.project_id,d.task_type,d.source_reference,d.template_version_reference,
                  d.parameters_reference,d.provider_profile_reference,d.requires_approval,d.attempts
        """)
        .param("now", now)
        .param("retry", now.plusSeconds(2))
        .query((rs, n) -> new DispatchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getBoolean(8), rs.getInt(9)))
        .optional().orElse(null);
  }

  private void markDispatched(UUID taskId) {
    jdbc.sql("UPDATE task_workflow_dispatch SET dispatched_at=:now,last_error_code=NULL WHERE task_id=:task")
        .param("now", OffsetDateTime.now()).param("task", taskId).update();
  }

  private void markFailure(DispatchRow row, String code) {
    long delay = Math.min(300L, 1L << Math.min(8, Math.max(1, row.attempts())));
    String safe = code == null ? "WORKFLOW_UNAVAILABLE" : code.substring(0, Math.min(MAX_ERROR_LENGTH, code.length()));
    jdbc.sql("UPDATE task_workflow_dispatch SET next_attempt_at=:next,last_error_code=:error WHERE task_id=:task")
        .param("next", OffsetDateTime.now().plusSeconds(delay)).param("error", safe).param("task", row.taskId()).update();
  }

  static String workflowId(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("task id is required");
    return "task-" + taskId;
  }

  private record DispatchRow(UUID taskId, UUID projectId, String taskType, String sourceReference,
                             String templateVersionReference, String parametersReference,
                             String providerProfileReference, boolean requiresApproval, int attempts) { }
}
