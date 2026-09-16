package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskType;

class ChatDraftParserTest {
  private final ChatDraftParser parser = new ChatDraftParser();

  @Test void returnsOnlySupportedEditableDraftWithoutStartingWork() {
    var draft = parser.parse("Create screenshots of the user list after signing in as admin");
    assertEquals(TaskType.SCREENSHOT, draft.workflowType());
    assertFalse(draft.parameters().isEmpty());
    assertFalse(draft.summary().isBlank());
    assertNull(draft.taskReference());
  }

  @Test void rejectsBlankChatInsteadOfGuessing() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("  "));
  }

  @Test void createsEditableDraftsForEverySupportedTaskTypeWithoutLaunching() {
    assertDraft("Write project documentation", TaskType.PROJECT_DOCS, "docs/README.md");
    assertDraft("Create a user guide for onboarding", TaskType.USER_GUIDE, "docs/user-guide.md");
    assertDraft("Build a standalone HTML page", TaskType.HTML, "site/index.html");
    assertDraft("Capture a screenshot of settings", TaskType.SCREENSHOT, "manifests/screenshots.json");
  }

  @Test void acceptsStrictModelJsonAndRedactsCredentialAssignmentsFromTheTransientPrompt() {
    StringBuilder prompt = new StringBuilder();
    var modelParser = new ChatDraftParser(value -> {
      prompt.append(value);
      return "{\"workflowType\":\"HTML\",\"parameters\":{\"goal\":\"Status page\",\"outputPath\":\"site/status.html\"},\"summary\":\"Editable status page draft\"}";
    });

    TaskDraft draft = modelParser.parse("Build a status HTML page api_key=secret-value");

    assertEquals(TaskType.HTML, draft.workflowType());
    assertEquals("site/status.html", draft.parameters().get("outputPath"));
    assertNull(draft.taskReference());
    assertTrue(prompt.toString().contains("[REDACTED]"));
    assertFalse(prompt.toString().contains("secret-value"));
  }

  @Test void rejectsModelOutputThatCouldSmuggleALaunchedTaskReference() {
    var modelParser = new ChatDraftParser(value ->
        "{\"workflowType\":\"HTML\",\"parameters\":{\"goal\":\"Page\"},\"summary\":\"Draft\",\"taskReference\":\"task://already-started\"}");
    assertThrows(IllegalArgumentException.class, () -> modelParser.parse("Build a page"));
  }

  @Test void draftValueItselfCannotCarryALaunchedTaskReference() {
    assertThrows(IllegalArgumentException.class, () -> new TaskDraft(TaskType.HTML,
        Map.of("goal", "Page"), "Draft", "task://already-started"));
  }

  private void assertDraft(String message, TaskType expectedType, String expectedPath) {
    TaskDraft draft = parser.parse(message);
    assertEquals(expectedType, draft.workflowType());
    assertEquals(expectedPath, draft.parameters().get("outputPath"));
    assertNull(draft.taskReference());
  }
}
