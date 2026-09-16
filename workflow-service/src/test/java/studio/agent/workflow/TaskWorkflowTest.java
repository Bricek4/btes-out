package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;

class TaskWorkflowTest {
  private static final String TASK_QUEUE = "workflow-service-test";
  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private DeterministicActivities activities;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TaskWorkflowImpl.class);
    activities = new DeterministicActivities();
    worker.registerActivitiesImplementations(activities);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    if (environment != null) environment.close();
  }

  @Test
  void pauseAndResumeSurviveBeforeActivityAndFinishWithArtifact() throws Exception {
    TaskWorkflow workflow = newWorkflow("pause-resume");
    WorkflowClient.start(workflow::run, input("pause-resume", false));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);

    waitForState(workflow, TaskStatus.RUNNING);
    workflow.pause();
    waitForState(workflow, TaskStatus.PAUSED);
    assertThat(workflow.state().status()).isEqualTo(TaskStatus.PAUSED);

    workflow.resume();
    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(
        new TaskWorkflowResult("pause-resume", TaskStatus.SUCCEEDED, "artifact://pause-resume", null));
  }

  @Test
  void approvalSignalResumesOnlyAfterChoiceAndRejectsInvalidDecision() throws Exception {
    TaskWorkflow workflow = newWorkflow("approval");
    WorkflowClient.start(workflow::run, input("approval", true));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);

    waitForState(workflow, TaskStatus.WAITING_FOR_APPROVAL);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.signal("approve", new ApprovalDecision("", null));
    assertThat(workflow.state().status()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);
    stub.signal("approve", new ApprovalDecision("approve", null));
    waitForState(workflow, TaskStatus.RUNNING);
    assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  @Test
  void cancelIsIdempotentAndProducesNoArtifact() throws Exception {
    TaskWorkflow workflow = newWorkflow("cancel");
    WorkflowClient.start(workflow::run, input("cancel", true));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);
    waitForState(workflow, TaskStatus.WAITING_FOR_APPROVAL);
    workflow.cancel();
    workflow.cancel();
    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(
        new TaskWorkflowResult("cancel", TaskStatus.CANCELED, null, "CANCELED_BY_USER"));
  }

  @Test
  void pauseDuringApprovalIsAppliedAfterApprovalBeforeActivity() throws Exception {
    TaskWorkflow workflow = newWorkflow("pause-approval");
    WorkflowClient.start(workflow::run, input("pause-approval", true));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);
    waitForState(workflow, TaskStatus.WAITING_FOR_APPROVAL);
    workflow.pause();
    assertThat(workflow.state().pauseRequested()).isTrue();
    workflow.approve(new ApprovalDecision("approve", null));
    waitForState(workflow, TaskStatus.PAUSED);
    workflow.resume();
    assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  @Test
  void activityMarkerAmbiguityCreatesTypedApprovalAndRetriesAfterApproval() throws Exception {
    var workflow = newWorkflow("runtime-approval");
    activities.approvalOnFirst = true;
    WorkflowClient.start(workflow::run, input("runtime-approval", false));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);
    waitForState(workflow, TaskStatus.WAITING_FOR_APPROVAL);
    assertThat(workflow.state().approvalRequest()).isEqualTo(new TaskApprovalRequest(
        "SCREENSHOT_ROUTE_AMBIGUITY", "users", "ROUTE_EVIDENCE_INSUFFICIENT", "approval://screenshot-route/users"));
    workflow.approve(new ApprovalDecision("approve", "approval://screenshot-route/users"));
    assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(activities.calls).isEqualTo(2);
    assertThat(activities.lastApprovedReference).isEqualTo("approval://screenshot-route/users");
  }

  @Test
  void approvedReferenceFromStartInputIsForwardedWithoutFreeFormPayload() throws Exception {
    var workflow = newWorkflow("preapproved-reference");
    WorkflowInput input = input("preapproved-reference", false)
        .withApprovedReference("approval://screenshot-route/users/sha256-abc");

    TaskWorkflowResult result = workflow.run(input);

    assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(activities.lastApprovedReference)
        .isEqualTo("approval://screenshot-route/users/sha256-abc");
  }

  @Test
  void runtimeApprovalIgnoresAReferenceThatDoesNotMatchPendingEvidence() {
    var workflow = newWorkflow("runtime-approval-binding");
    activities.approvalOnFirst = true;
    WorkflowClient.start(workflow::run, input("runtime-approval-binding", false));
    waitForState(workflow, TaskStatus.WAITING_FOR_APPROVAL);

    workflow.approve(new ApprovalDecision("approve", "approval://screenshot-route/another-marker"));

    assertThat(workflow.state().status()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);
    workflow.cancel();
  }

  @Test
  void pauseDuringActivityWaitsForResumeBeforePublishingResult() throws Exception {
    var workflow = newWorkflow("pause-during-activity");
    activities.block = true;
    WorkflowClient.start(workflow::run, input("pause-during-activity", false));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);
    waitForState(workflow, TaskStatus.RUNNING);
    assertThat(activities.entered.await(5, TimeUnit.SECONDS)).isTrue();
    workflow.pause();
    assertThat(workflow.state().status()).isEqualTo(TaskStatus.RUNNING);
    activities.release.countDown();
    waitForState(workflow, TaskStatus.PAUSED);
    workflow.resume();
    assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  @Test
  void cancelDuringActivityCancelsTheScopeAndNeverPublishesTheActivityResult() throws Exception {
    var workflow = newWorkflow("cancel-during-activity");
    activities.block = true;
    WorkflowClient.start(workflow::run, input("cancel-during-activity", false));
    CompletableFuture<TaskWorkflowResult> result = WorkflowStub.fromTyped(workflow)
        .getResultAsync(TaskWorkflowResult.class);
    waitForState(workflow, TaskStatus.RUNNING);
    assertThat(activities.entered.await(5, TimeUnit.SECONDS)).isTrue();
    workflow.cancel();
    activities.release.countDown();
    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(
        new TaskWorkflowResult("cancel-during-activity", TaskStatus.CANCELED, null, "CANCELED_BY_USER"));
  }

  private TaskWorkflow newWorkflow(String id) {
    return client.newWorkflowStub(TaskWorkflow.class,
        WorkflowOptions.newBuilder().setWorkflowId("task-" + id).setTaskQueue(TASK_QUEUE).build());
  }

  private static WorkflowInput input(String id, boolean approval) {
    return new WorkflowInput(id, "project-" + id, TaskType.PROJECT_DOCS, "source://" + id,
        "template://default", "parameters://" + id, "provider://default", approval);
  }

  private static void waitForState(TaskWorkflow workflow, TaskStatus status) {
    for (int i = 0; i < 100; i++) {
      if (workflow.state().status() == status) return;
      try {
        Thread.sleep(Duration.ofMillis(10));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    assertThat(workflow.state().status()).isEqualTo(status);
  }

  static final class DeterministicActivities implements TaskActivities {
    private volatile boolean block;
    private volatile boolean approvalOnFirst;
    private int calls;
    private String lastApprovedReference;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public ActivityOutcome execute(WorkflowInput input) {
      calls++;
      lastApprovedReference = input.approvedReference();
      if (approvalOnFirst && calls == 1) {
        return new ActivityOutcome(TaskStatus.WAITING_FOR_APPROVAL, null, null,
        new TaskApprovalRequest("SCREENSHOT_ROUTE_AMBIGUITY", "users",
                "ROUTE_EVIDENCE_INSUFFICIENT", "approval://screenshot-route/users"));
      }
      if (block) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("activity test release timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("activity test interrupted");
        }
      }
      return new ActivityOutcome(TaskStatus.SUCCEEDED, "artifact://" + input.taskId(), null);
    }
  }

}
