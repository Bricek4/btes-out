package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import studio.agent.contracts.TaskStatus;

class WorkflowBridgeHttpTest {
  private static final String TOKEN = "0123456789abcdef0123456789abcdef";
  private RecordingGateway gateway;
  private MockMvc http;

  @BeforeEach
  void setUp() {
    gateway = new RecordingGateway();
    http = MockMvcBuilders.standaloneSetup(new WorkflowBridgeController(gateway))
        .setControllerAdvice(new WorkflowApiExceptionHandler())
        .addFilters(new WorkflowServiceTokenFilter(TOKEN))
        .build();
  }

  @Test
  void bearerTokenIsRequiredBeforeAnyWorkflowOperation() throws Exception {
    http.perform(get("/internal/workflows/tasks/task-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    assertThat(gateway.operations).isEmpty();
  }

  @Test
  void validOpaqueStartRequestCreatesWorkflowWithoutReturningReferences() throws Exception {
    http.perform(post("/internal/workflows/tasks/task-1/start")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "taskId":"task-1",
                  "projectId":"project-1",
                  "type":"PROJECT_DOCS",
                  "sourceReference":"source://project-1/archive",
                  "templateVersionReference":"template://public/default/v1",
                  "parametersReference":"parameters://task-1/v1",
                  "providerProfileReference":"provider://member/profile-1",
                  "requiresApproval":false
                }
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.taskId").value("task-1"))
        .andExpect(jsonPath("$.workflowId").value("task-task-1"))
        .andExpect(jsonPath("$.started").value(true))
        .andExpect(jsonPath("$.sourceReference").doesNotExist())
        .andExpect(jsonPath("$.providerProfileReference").doesNotExist());

    assertThat(gateway.started.taskId()).isEqualTo("task-1");
    assertThat(gateway.started.sourceReference()).isEqualTo("source://project-1/archive");
  }

  @Test
  void rawOrUnknownFieldsAreRejectedBeforeStartingWorkflow() throws Exception {
    http.perform(post("/internal/workflows/tasks/task-1/start")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "taskId":"task-1",
                  "projectId":"project-1",
                  "type":"PROJECT_DOCS",
                  "sourceReference":"https://git.example/repository?token=secret",
                  "templateVersionReference":"template://public/default/v1",
                  "parametersReference":"parameters://task-1/v1",
                  "providerProfileReference":"provider://member/profile-1",
                  "requiresApproval":false,
                  "prompt":"retain this"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(gateway.started).isNull();
  }

  @Test
  void pathTaskIdMustMatchTheWorkflowInputTaskId() throws Exception {
    http.perform(post("/internal/workflows/tasks/task-1/start")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "taskId":"task-2",
                  "projectId":"project-1",
                  "type":"PROJECT_DOCS",
                  "sourceReference":"source://project-1/archive",
                  "templateVersionReference":"template://public/default/v1",
                  "parametersReference":"parameters://task-1/v1",
                  "providerProfileReference":"provider://member/profile-1",
                  "requiresApproval":false
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(gateway.started).isNull();
  }

  @Test
  void queryReturnsOnlySafeWorkflowState() throws Exception {
    gateway.state = new TaskWorkflowState(TaskStatus.WAITING_FOR_APPROVAL, null, null,
        true, false, false, new TaskApprovalRequest("SCREENSHOT_ROUTE_AMBIGUITY", "users",
        "ROUTE_EVIDENCE_INSUFFICIENT", "approval://screenshot-route/users/sha256-abc"));

    http.perform(get("/internal/workflows/tasks/task-1")
            .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value("task-1"))
        .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
        .andExpect(jsonPath("$.approvalRequest.reference")
            .value("approval://screenshot-route/users/sha256-abc"))
        .andExpect(jsonPath("$.sourceReference").doesNotExist());
  }

  @Test
  void explicitSignalRoutesCarryNoFreeFormText() throws Exception {
    http.perform(post("/internal/workflows/tasks/task-1/pause")
            .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.signal").value("pause"));
    http.perform(post("/internal/workflows/tasks/task-1/resume")
            .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.signal").value("resume"));
    http.perform(post("/internal/workflows/tasks/task-1/cancel")
            .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.signal").value("cancel"));
    http.perform(post("/internal/workflows/tasks/task-1/approve")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"decision":"approve","approvedReference":"approval://screenshot-route/users/sha256-abc"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.signal").value("approve"));

    assertThat(gateway.operations).containsExactly("pause", "resume", "cancel", "approve");
    assertThat(gateway.approval.approvedReference())
        .isEqualTo("approval://screenshot-route/users/sha256-abc");
  }

  @Test
  void oversizedOrMismatchedApprovalPayloadIsRejected() throws Exception {
    http.perform(post("/internal/workflows/tasks/task-1/approve")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"decision":"approve","approvedReference":"plain text","comment":"ship it"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    String largeBody = "{\"decision\":\"approve\",\"approvedReference\":\"approval://screenshot-route/"
        + "x".repeat(20_000) + "\"}";
    http.perform(post("/internal/workflows/tasks/task-1/approve")
            .header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(largeBody))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));

    assertThat(gateway.approval).isNull();
  }

  private static final class RecordingGateway implements WorkflowTaskGateway {
    private final List<String> operations = new ArrayList<>();
    private WorkflowInput started;
    private ApprovalDecision approval;
    private TaskWorkflowState state = new TaskWorkflowState(TaskStatus.RUNNING, null, null,
        false, false, false, null);

    @Override
    public WorkflowStartResult start(WorkflowInput input) {
      started = input;
      return new WorkflowStartResult(input.taskId(), "task-" + input.taskId(), true);
    }

    @Override
    public TaskWorkflowState query(String taskId) {
      operations.add("query");
      return state;
    }

    @Override public void pause(String taskId) { operations.add("pause"); }
    @Override public void resume(String taskId) { operations.add("resume"); }
    @Override public void cancel(String taskId) { operations.add("cancel"); }

    @Override
    public void approve(String taskId, ApprovalDecision decision) {
      operations.add("approve");
      approval = decision;
    }
  }
}
