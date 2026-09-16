package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskType;

class ArtifactGenerationServiceTest {
  @Test void validatesModelGeneratedDocumentBeforeArtifactCreation() {
    var service = new ArtifactGenerationService(prompt -> "# Neutral guide\nEvidence based");
    var artifact = service.generate(TaskType.USER_GUIDE, "route /home", "# {{content}}", "added /home");
    assertEquals("docs/user-guide.md", artifact.path());
    assertTrue(artifact.content().contains("Neutral guide"));
  }

  @Test void usesTheSelectedTemplatePathAndRedactsSecretsFromTransientPrompt() {
    StringBuilder promptSent = new StringBuilder();
    var service = new ArtifactGenerationService(prompt -> { promptSent.append(prompt); return "## User endpoints\nGET /users"; });
    var artifact = service.generate(new ArtifactGenerationService.GenerationRequest(TaskType.PROJECT_DOCS,
        "GET /users\napi_key=sk-local-secret", "docs/api.md", "markdown",
        "# {{title}}\n{{sourceChangeSummary}}\n{{content}}", "Example API", Map.of("audience", "developer"),
        "added /users", "", Set.of("admin")));

    assertEquals("docs/api.md", artifact.path());
    assertTrue(artifact.content().contains("added /users"));
    assertTrue(artifact.content().contains("GET /users"));
    assertTrue(promptSent.toString().contains("[REDACTED]"));
    assertFalse(promptSent.toString().contains("sk-local-secret"));
  }

  @Test void preservesManualSectionAndPassesPriorContentOnlyForIncrementalUpdates() {
    String previous = "# Old\n<!-- agent-studio:manual:start -->Keep this<!-- agent-studio:manual:end -->";
    StringBuilder promptSent = new StringBuilder();
    var service = new ArtifactGenerationService(prompt -> { promptSent.append(prompt); return "Updated facts"; });
    var artifact = service.generate(new ArtifactGenerationService.GenerationRequest(TaskType.PROJECT_DOCS,
        "route /users", "docs/guide.md", "markdown",
        "# Guide\n{{content}}\n<!-- agent-studio:manual:start -->default<!-- agent-studio:manual:end -->",
        "Guide", Map.of(), "changed /users", previous, Set.of(), Set.of(), true));

    assertTrue(artifact.content().contains("Keep this"));
    assertTrue(artifact.content().contains("Updated facts"));
    assertTrue(promptSent.toString().contains("Existing artifact for an incremental update"));
  }

  @Test void sanitizesGeneratedHtmlAndReturnsAssetValidationFindings() {
    var service = new ArtifactGenerationService(prompt ->
        "<h1>Overview</h1><img src=assets/missing.png onerror=alert(1)><script>alert(2)</script>");
    var artifact = service.generate(new ArtifactGenerationService.GenerationRequest(TaskType.HTML,
        "route /home", "site/index.html", "html",
        "<!doctype html><html><head><title>{{title}}</title></head><body><main>{{content}}</main></body></html>",
        "Overview", Map.of(), "", "", Set.of()));

    assertFalse(artifact.content().toLowerCase().contains("onerror"));
    assertFalse(artifact.content().toLowerCase().contains("<script"));
    assertFalse(artifact.validationReport().valid());
    assertTrue(artifact.validationReport().issues().stream().anyMatch(issue -> issue.startsWith("ASSET_REFERENCE_INVALID")));
  }

  @Test void rejectsEmptyModelOutput() {
    var service = new ArtifactGenerationService(prompt -> " ");
    assertThrows(IllegalArgumentException.class, () -> service.generate(TaskType.PROJECT_DOCS, "routes", "template", ""));
  }

  @Test void rejectsGeneratedMarkersThatReferenceAnUnapprovedLoginProfile() {
    String marker = "<!-- agent-studio:screenshot:v1 {\"id\":\"users\",\"loginProfileRef\":\"owner\",\"target\":\"users\",\"caption\":\"Users\"} -->";
    var service = new ArtifactGenerationService(prompt -> "## Users\n" + marker);
    var request = new ArtifactGenerationService.GenerationRequest(TaskType.USER_GUIDE,
        "route /users", "docs/users.md", "markdown", "# {{title}}\n{{content}}", "Users",
        Map.of(), "", "", Set.of("admin"));

    assertThrows(IllegalArgumentException.class, () -> service.generate(request));
  }
}
