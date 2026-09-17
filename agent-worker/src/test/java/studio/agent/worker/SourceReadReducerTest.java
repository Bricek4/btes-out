package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceReadReducerTest {
  @Test
  void reduces_large_per_file_chunk_summaries_only_after_all_are_present() {
    var chunks = List.of(
        new SourceReadChunk("one", "src/Main.java", 0, 0, 100, "a", "source one", SourceReadStatus.PLANNED),
        new SourceReadChunk("two", "src/Main.java", 1, 100, 200, "b", "source two", SourceReadStatus.PLANNED));
    var file = new SourceReadFile("src/Main.java", SourceFileCategory.SOURCE, 200, "file-sha", chunks,
        SourceReadStatus.PLANNED, null);
    var plan = new SourceReadPlan("archive-sha", 1, 200, List.of(file));
    var result = new SourceReadResult(plan,
        List.of(new SourceReadCoverageEntry("src/Main.java", "one", SourceReadStatus.ANALYZED, null),
            new SourceReadCoverageEntry("src/Main.java", "two", SourceReadStatus.ANALYZED, null)),
        Map.of("one", "{\"facts\":[\"one\"]}".repeat(80), "two", "{\"facts\":[\"two\"]}".repeat(80)),
        Map.of("src/Main.java", new SourceFacts("src/Main.java", List.of("Main"), List.of(), List.of(), List.of(), List.of(), List.of())),
        2, 0);
    List<String> prompts = new ArrayList<>();

    String context = SourceReadReducer.reduce(result, prompt -> {
      prompts.add(prompt);
      return "all file facts";
    }, 1_000);

    assertTrue(prompts.size() == 1);
    assertTrue(prompts.getFirst().contains("one") && prompts.getFirst().contains("two"));
    assertTrue(context.contains("all file facts"));
  }

  @Test
  void keeps_small_summaries_without_an_extra_model_call() {
    var chunk = new SourceReadChunk("one", "README.md", 0, 0, 8, "a", "readme", SourceReadStatus.PLANNED);
    var file = new SourceReadFile("README.md", SourceFileCategory.DOCUMENT, 8, "file-sha", List.of(chunk),
        SourceReadStatus.PLANNED, null);
    var plan = new SourceReadPlan("archive-sha", 1, 8, List.of(file));
    var result = new SourceReadResult(plan,
        List.of(new SourceReadCoverageEntry("README.md", "one", SourceReadStatus.ANALYZED, null)),
        Map.of("one", "{\"facts\":[\"readme\"]}"), Map.of(), 1, 0);

    String context = SourceReadReducer.reduce(result, prompt -> { throw new AssertionError("not expected"); }, 1_000);

    assertTrue(context.contains("readme"));
  }
}
