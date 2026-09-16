package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;

class TemporalWorkflowTaskGatewayTest {
  private static final String QUEUE = "workflow-bridge-gateway-test";
  private TestWorkflowEnvironment environment;
  private TemporalWorkflowTaskGateway gateway;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(QUEUE);
    worker.registerWorkflowImplementationTypes(TaskWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ApprovalOnceActivities());
    environment.start();
    gateway = new TemporalWorkflowTaskGateway(environment.getWorkflowClient(), QUEUE);
  }

  @AfterEach
  void tearDown() {
    if (environment != null) environment.close();
  }

  @Test
  void repeatedStartIsIdempotentOnlyForTheExactSameOpaqueInput() {
    WorkflowInput input = input("idempotent");

    assertThat(gateway.start(input).started()).isTrue();
    waitForStatus("idempotent", TaskStatus.WAITING_FOR_APPROVAL);
    assertThat(gateway.start(input).started()).isFalse();

    WorkflowInput different = new WorkflowInput("idempotent", "project-idempotent",
        TaskType.PROJECT_DOCS, "source://different", "template://default",
        "parameters://idempotent", "provider://default", false);
    assertThatThrownBy(() -> gateway.start(different))
        .isInstanceOf(WorkflowStartConflictException.class);
  }

  @Test
  void completedWorkflowCannotBeStartedAsANewRun() {
    WorkflowInput input = input("completed-idempotent");
    gateway.start(input);
    waitForStatus("completed-idempotent", TaskStatus.WAITING_FOR_APPROVAL);
    gateway.approve("completed-idempotent",
        new ApprovalDecision("approve", "approval://screenshot-route/users/sha256-abc"));
    waitForStatus("completed-idempotent", TaskStatus.SUCCEEDED);

    assertThat(gateway.start(input).started()).isFalse();
    assertThat(gateway.query("completed-idempotent").status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  @Test
  void approvalMustBindToThePendingEvidenceReferenceBeforeSignalIsAccepted() {
    gateway.start(input("approval-binding"));
    waitForStatus("approval-binding", TaskStatus.WAITING_FOR_APPROVAL);

    assertThatThrownBy(() -> gateway.approve("approval-binding",
        new ApprovalDecision("approve", "approval://screenshot-route/wrong")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(gateway.query("approval-binding").status())
        .isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);

    gateway.approve("approval-binding",
        new ApprovalDecision("approve", "approval://screenshot-route/users/sha256-abc"));
    waitForStatus("approval-binding", TaskStatus.SUCCEEDED);
  }

  @Test
  void missingWorkflowFailsClosedForQueriesAndSignals() {
    assertThatThrownBy(() -> gateway.query("missing"))
        .isInstanceOf(WorkflowTaskNotFoundException.class);
    assertThatThrownBy(() -> gateway.cancel("missing"))
        .isInstanceOf(WorkflowTaskNotFoundException.class);
  }

  private WorkflowInput input(String id) {
    return new WorkflowInput(id, "project-" + id, TaskType.PROJECT_DOCS, "source://" + id,
        "template://default", "parameters://" + id, "provider://default", false);
  }

  private void waitForStatus(String taskId, TaskStatus status) {
    for (int i = 0; i < 100; i++) {
      if (gateway.query(taskId).status() == status) return;
      try {
        Thread.sleep(Duration.ofMillis(10));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    assertThat(gateway.query(taskId).status()).isEqualTo(status);
  }

  static final class ApprovalOnceActivities implements TaskActivities {
    private int calls;

    @Override
    public ActivityOutcome execute(WorkflowInput input) {
      calls++;
      if (calls == 1) {
        return new ActivityOutcome(TaskStatus.WAITING_FOR_APPROVAL, null, null,
            new TaskApprovalRequest("SCREENSHOT_ROUTE_AMBIGUITY", "users",
                "ROUTE_EVIDENCE_INSUFFICIENT",
                "approval://screenshot-route/users/sha256-abc"));
      }
      return new ActivityOutcome(TaskStatus.SUCCEEDED, "artifact://" + input.taskId(), null);
    }
  }

}
