package studio.agent.platform.workflow;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Set;
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

  public void enqueueSignal(UUID taskId, String action) {
    if (taskId == null || action == null || !Set.of("pause", "resume", "cancel").contains(action)) {
      throw new IllegalArgumentException("workflow signal is invalid");
    }
    enqueueCommand(taskId, action, null, null);
  }

  public void enqueueApproval(UUID taskId, String decision, String approvedReference) {
    if (taskId == null || decision == null) throw new IllegalArgumentException("approval command is invalid");
    String normalized = decision.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("approve", "approved", "continue", "reject", "rejected", "cancel").contains(normalized)) {
      throw new IllegalArgumentException("approval decision is invalid");
    }
    if (approvedReference != null && !approvedReference.matches("approval://screenshot-route/[A-Za-z0-9][A-Za-z0-9._/-]*")) {
      throw new IllegalArgumentException("approval reference is invalid");
    }
    enqueueCommand(taskId, "approve", normalized, approvedReference);
  }

  private void enqueueCommand(UUID taskId, String action, String decision, String approvedReference) {
    jdbc.sql("""
        INSERT INTO task_workflow_commands(id,task_id,action,decision,approved_reference,attempts,next_attempt_at,created_at)
        VALUES(:id,:task,:action,:decision,:reference,0,:next,:created)
        """).param("id", UUID.randomUUID()).param("task", taskId).param("action", action)
        .param("decision", decision).param("reference", approvedReference)
        .param("next", OffsetDateTime.now()).param("created", OffsetDateTime.now()).update();
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

  @Scheduled(fixedDelayString = "${agent-studio.workflow.dispatch-interval-ms:1000}")
  public void dispatchCommands() {
    for (int i = 0; i < BATCH_SIZE; i++) {
      CommandRow row = claimCommand();
      if (row == null) return;
      try {
        var body = new LinkedHashMap<String, String>();
        if ("approve".equals(row.action())) {
          body.put("decision", row.decision());
          if (row.approvedReference() != null) body.put("approvedReference", row.approvedReference());
        }
        workflow.post().uri("/internal/workflows/tasks/{taskId}/{action}", row.taskId(), row.action())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .body(body)
            .retrieve().toBodilessEntity();
        markCommandDelivered(row.id());
      } catch (RestClientResponseException failure) {
        if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 409) {
          markCommandFailure(row, "WORKFLOW_COMMAND_REJECTED");
        } else {
          markCommandFailure(row, "WORKFLOW_HTTP_" + failure.getStatusCode().value());
        }
      } catch (RuntimeException failure) {
        markCommandFailure(row, "WORKFLOW_UNAVAILABLE");
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

  private CommandRow claimCommand() {
    OffsetDateTime now = OffsetDateTime.now();
    return jdbc.sql("""
        UPDATE task_workflow_commands c
           SET attempts=c.attempts+1,next_attempt_at=:retry
         WHERE c.id=(SELECT candidate.id FROM task_workflow_commands candidate
          WHERE candidate.delivered_at IS NULL AND candidate.next_attempt_at<=:now
          ORDER BY candidate.created_at FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING c.id,c.task_id,c.action,c.decision,c.approved_reference,c.attempts
        """).param("now", now).param("retry", now.plusSeconds(2))
        .query((rs, n) -> new CommandRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6))).optional().orElse(null);
  }

  private void markCommandDelivered(UUID id) {
    jdbc.sql("UPDATE task_workflow_commands SET delivered_at=:now,last_error_code=NULL WHERE id=:id")
        .param("now", OffsetDateTime.now()).param("id", id).update();
  }

  private void markCommandFailure(CommandRow row, String code) {
    long delay = Math.min(300L, 1L << Math.min(8, Math.max(1, row.attempts())));
    jdbc.sql("UPDATE task_workflow_commands SET next_attempt_at=:next,last_error_code=:error WHERE id=:id")
        .param("next", OffsetDateTime.now().plusSeconds(delay))
        .param("error", code.substring(0, Math.min(MAX_ERROR_LENGTH, code.length())))
        .param("id", row.id()).update();
  }

  static String workflowId(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("task id is required");
    return "task-" + taskId;
  }

  private record DispatchRow(UUID taskId, UUID projectId, String taskType, String sourceReference,
                             String templateVersionReference, String parametersReference,
                             String providerProfileReference, boolean requiresApproval, int attempts) { }
  private record CommandRow(UUID id, UUID taskId, String action, String decision,
                            String approvedReference, int attempts) { }
}
