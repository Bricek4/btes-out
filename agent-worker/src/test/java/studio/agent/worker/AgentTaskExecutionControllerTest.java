package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;

class AgentTaskExecutionControllerTest {
  @Test void acceptsTheWorkflowWireShapeButNeverFabricatesAnArtifactSuccess() {
    UUID task = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    var controller = new AgentTaskExecutionController((request, approved) -> new WorkerExecutionService.LocalWorkerResult(
        new studio.agent.contracts.WorkerTaskResult(request.taskId(), TaskStatus.FAILED, null,
            "AGENT_EXECUTION_PIPELINE_UNAVAILABLE"), null, null));

    var outcome = controller.execute(task.toString(), new AgentTaskExecutionController.WorkflowInput(
        task.toString(), project.toString(), TaskType.PROJECT_DOCS, "source://1", "template://1",
        "parameters://1", "provider://1", false));

    assertEquals(TaskStatus.FAILED, outcome.status());
    assertNull(outcome.artifactReference());
    assertEquals("AGENT_EXECUTION_PIPELINE_UNAVAILABLE", outcome.failureCode());
  }

  @Test void turnsNonterminalScreenshotProposalIntoAnExplicitFailure() {
    var controller = new AgentTaskExecutionController((request, approved) -> new WorkerExecutionService.LocalWorkerResult(
        null, null, new WorkerExecutionService.ApprovalBridge("SCREENSHOT_ROUTE_AMBIGUITY", "users",
            "ROUTE_EVIDENCE_INSUFFICIENT", "approval://screenshot-route/users")));
    UUID task = UUID.randomUUID();
    var outcome = controller.execute(task.toString(), new AgentTaskExecutionController.WorkflowInput(
        task.toString(), UUID.randomUUID().toString(), TaskType.SCREENSHOT,
        "source://1", "template://1", "parameters://1", "provider://1", true));
    assertEquals(TaskStatus.WAITING_FOR_APPROVAL, outcome.status());
    assertNull(outcome.artifactReference());
    assertNull(outcome.failureCode());
    assertEquals("users", outcome.approval().markerId());
    try {
      String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(outcome);
      assertTrue(json.contains("\"status\":\"WAITING_FOR_APPROVAL\""));
      assertTrue(json.contains("\"approval\""));
      assertFalse(json.contains("failureCode"));
      assertFalse(json.contains("password"));
    } catch (tools.jackson.core.JacksonException impossible) {
      fail(impossible);
    }
  }

  @Test void rejectsTaskIdMismatchBeforeExecution() {
    var controller = new AgentTaskExecutionController((request, approved) -> new WorkerExecutionService.LocalWorkerResult(
        new studio.agent.contracts.WorkerTaskResult(request.taskId(), TaskStatus.FAILED, null,
            "SHOULD_NOT_RUN"), null, null));
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

  @Test void springRuntimeProvidesThePlatformBrowserModelAndExecutionPipelineBeans() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
          "AGENT_WORKER_TOKEN", "agent-token",
          "PLATFORM_API_URL", "http://platform.test",
          "BROWSER_WORKER_URL", "http://browser.test")));
      context.register(AgentWorkerSecurityConfiguration.class, AgentTaskExecutionController.class);
      context.refresh();
      assertNotNull(context.getBean(AgentPlatformClient.class));
      assertNotNull(context.getBean(BrowserWorkerClient.class));
      assertNotNull(context.getBean(WorkerExecutionService.class));
      assertNotNull(context.getBean(AgentTaskExecutionController.class));
    }
  }
}
