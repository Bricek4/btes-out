package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFactExtractorTest {
  @Test
  void extracts_routes_symbols_dependencies_and_ui_targets_without_calling_the_model() {
    var file = new SourceReadFile("src/TaskController.java", SourceFileCategory.SOURCE, 0, "sha",
        List.of(new SourceReadChunk("chunk", "src/TaskController.java", 0, 0, 100, "chunk-sha",
            "import java.util.UUID;\n@RestController\n@GetMapping(\"/api/tasks\")\nclass TaskController {}\n", SourceReadStatus.PLANNED)),
        SourceReadStatus.PLANNED, null);

    SourceFacts facts = SourceFactExtractor.extract(file);

    assertEquals(List.of("TaskController"), facts.symbols());
    assertEquals(List.of("GET /api/tasks"), facts.routes());
    assertTrue(facts.dependencies().contains("java.util.UUID"));
  }

  @Test
  void extracts_frontend_routes_and_semantic_targets() {
    var file = new SourceReadFile("src/routes.ts", SourceFileCategory.SOURCE, 0, "sha",
        List.of(new SourceReadChunk("chunk", "src/routes.ts", 0, 0, 180, "chunk-sha",
            "import { createRouter } from 'vue-router'\n{ path: '/settings', component: SettingsView }\n<button aria-label=\"Save settings\">Save</button>\n", SourceReadStatus.PLANNED)),
        SourceReadStatus.PLANNED, null);

    SourceFacts facts = SourceFactExtractor.extract(file);

    assertTrue(facts.dependencies().contains("vue-router"));
    assertTrue(facts.routes().contains("/settings"));
    assertEquals(List.of("Save settings"), facts.uiTargets());
  }
}
