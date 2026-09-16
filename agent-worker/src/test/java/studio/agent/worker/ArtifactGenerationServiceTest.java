package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskType;

class ArtifactGenerationServiceTest {
  @Test void validatesModelGeneratedDocumentBeforeArtifactCreation() {
    var service = new ArtifactGenerationService(prompt -> "# Neutral guide\nEvidence based");
    var artifact = service.generate(TaskType.USER_GUIDE, "route /home", "# {{content}}", "added /home");
    assertEquals("docs/generated.md", artifact.path());
    assertTrue(artifact.content().contains("Neutral guide"));
  }
  @Test void rejectsEmptyModelOutput() {
    var service = new ArtifactGenerationService(prompt -> " ");
    assertThrows(IllegalArgumentException.class, () -> service.generate(TaskType.PROJECT_DOCS, "routes", "template", ""));
  }
}
