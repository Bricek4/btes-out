package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

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
}
