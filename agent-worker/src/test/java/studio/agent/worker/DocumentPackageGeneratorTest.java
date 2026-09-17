package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskType;

class DocumentPackageGeneratorTest {
  @Test
  void creates_reference_style_sections_and_dynamic_module_diagrams() {
    var chunk = new SourceReadChunk("chunk", "platform-api/src/TaskController.java", 0, 0, 20,
        "chunk-sha", "@GetMapping(\"/tasks\") class TaskController {}", SourceReadStatus.PLANNED);
    var file = new SourceReadFile("platform-api/src/TaskController.java", SourceFileCategory.SOURCE, 48,
        "file-sha", List.of(chunk), SourceReadStatus.PLANNED, null);
    var plan = new SourceReadPlan("archive-sha", 1, 48, List.of(file));
    var result = new SourceReadResult(plan,
        List.of(new SourceReadCoverageEntry(file.path(), chunk.chunkId(), SourceReadStatus.ANALYZED, null)),
        Map.of(chunk.chunkId(), "{\"facts\":[\"task endpoint\"]}"),
        Map.of(file.path(), new SourceFacts(file.path(), List.of("TaskController"), List.of("GET /tasks"), List.of(), List.of("spring-web"), List.of(), List.of())),
        1, 0);
    var request = new ArtifactGenerationService.GenerationRequest(TaskType.PROJECT_DOCS, "ignored", "docs/README.md",
        "markdown", "# {{title}}\n\n{{content}}", "Architecture", Map.of(), "", "", java.util.Set.of());

    var packageResult = DocumentPackageGenerator.generate(request, result, prompt -> "Generated section");

    assertEquals("docs/README.md", packageResult.documents().getFirst().path());
    assertTrue(packageResult.documents().stream().map(GeneratedArtifact::path)
        .anyMatch(path -> path.equals("docs/03-system-architecture.md")));
    assertTrue(packageResult.documents().stream().map(GeneratedArtifact::path)
        .anyMatch(path -> path.startsWith("docs/modules/")));
    assertTrue(packageResult.diagrams().stream().allMatch(diagram -> diagram.content().contains("<svg")));
    assertTrue(packageResult.documentMapJson().contains("source-read-coverage"));
  }
}
