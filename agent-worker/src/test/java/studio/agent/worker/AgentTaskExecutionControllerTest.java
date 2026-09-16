package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;

class AgentTaskExecutionControllerTest {
  @Test void acceptsTheWorkflowWireShapeButNeverFabricatesAnArtifactSuccess() {
    UUID task = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    var controller = new AgentTaskExecutionController(new WorkerExecutionService());

    var outcome = controller.execute(task.toString(), new AgentTaskExecutionController.WorkflowInput(
        task.toString(), project.toString(), TaskType.PROJECT_DOCS, "source://1", "template://1",
        "parameters://1", "provider://1", false));

    assertEquals(TaskStatus.FAILED, outcome.status());
    assertNull(outcome.artifactReference());
    assertEquals("AGENT_EXECUTION_PIPELINE_UNAVAILABLE", outcome.failureCode());
  }

  @Test void turnsNonterminalScreenshotProposalIntoAnExplicitFailure() {
    var controller = new AgentTaskExecutionController(new WorkerExecutionService());
    UUID task = UUID.randomUUID();
    var outcome = controller.execute(task.toString(), new AgentTaskExecutionController.WorkflowInput(
        task.toString(), UUID.randomUUID().toString(), TaskType.SCREENSHOT,
        "source://1", "template://1", "parameters://1", "provider://1", true));
    assertEquals(TaskStatus.FAILED, outcome.status());
    assertNull(outcome.artifactReference());
    assertEquals("SCREENSHOT_APPROVAL_REQUIRED", outcome.failureCode());
  }

  @Test void rejectsTaskIdMismatchBeforeExecution() {
    var controller = new AgentTaskExecutionController(new WorkerExecutionService());
    var input = new AgentTaskExecutionController.WorkflowInput(UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), TaskType.HTML, "source://1", "template://1",
        "parameters://1", "provider://1", false);
    assertThrows(IllegalArgumentException.class, () -> controller.execute(UUID.randomUUID().toString(), input));
  }

  @Test void agentTokenRejectsMissingOrDifferentCredentialsWithoutEchoingThem() {
    var token = new AgentWorkerToken("agent-secret");
    assertDoesNotThrow(() -> token.require("Bearer agent-secret"));
    SecurityException failure = assertThrows(SecurityException.class,
        () -> token.require("Bearer other-secret"));
    assertEquals("AGENT_WORKER_UNAUTHORIZED", failure.getMessage());
    assertFalse(failure.toString().contains("other-secret"));
    assertThrows(SecurityException.class, () -> token.require(null));
  }
}
