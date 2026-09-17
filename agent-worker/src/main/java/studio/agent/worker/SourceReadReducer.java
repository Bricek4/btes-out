package studio.agent.worker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Collapses complete chunk analyses hierarchically so final generation never drops a prefix. */
public final class SourceReadReducer {
  private static final int DEFAULT_FILE_CONTEXT_CHARS = 48_000;
  private static final int DEFAULT_PROJECT_CONTEXT_CHARS = 220_000;

  private SourceReadReducer() { }

  public static String reduce(SourceReadResult result, ArtifactGenerationService.ModelGateway model) {
    return reduce(result, model, DEFAULT_FILE_CONTEXT_CHARS);
  }

  static String reduce(SourceReadResult result, ArtifactGenerationService.ModelGateway model,
      int maxFileContextChars) {
    Objects.requireNonNull(result, "source read result is required");
    Objects.requireNonNull(model, "model is required");
    if (!result.complete()) throw new SourceReadException("SOURCE_READ_INCOMPLETE");
    if (maxFileContextChars < 1_000) throw new IllegalArgumentException("file context limit is too small");

    var fileSummaries = new LinkedHashMap<String, String>();
    for (SourceReadFile file : result.plan().files()) {
      if (file.status() == SourceReadStatus.SKIPPED) continue;
      String input = fileInput(result, file);
      fileSummaries.put(file.path(), input.length() <= maxFileContextChars
          ? input : reduceOne(model, "file " + file.path(), input));
    }
    String project = join(fileSummaries);
    if (project.length() <= DEFAULT_PROJECT_CONTEXT_CHARS) return project;

    var directorySummaries = new LinkedHashMap<String, String>();
    var grouped = new LinkedHashMap<String, StringBuilder>();
    fileSummaries.forEach((path, summary) -> grouped.computeIfAbsent(directory(path), ignored -> new StringBuilder())
        .append("\n--- ").append(path).append(" ---\n").append(summary));
    grouped.forEach((directory, summary) -> directorySummaries.put(directory,
        summary.length() <= maxFileContextChars * 4 ? summary.toString()
            : reduceOne(model, "directory " + directory, summary.toString())));
    project = join(directorySummaries);
    return project.length() <= DEFAULT_PROJECT_CONTEXT_CHARS
        ? project : reduceOne(model, "project", project);
  }

  private static String fileInput(SourceReadResult result, SourceReadFile file) {
    var out = new StringBuilder();
    SourceFacts facts = result.facts().get(file.path());
    if (facts != null) out.append("Static facts: ").append(facts).append('\n');
    for (SourceReadChunk chunk : file.chunks()) {
      String summary = result.summaries().get(chunk.chunkId());
      if (summary == null) throw new SourceReadException("SOURCE_READ_SUMMARY_MISSING");
      out.append("Chunk ").append(chunk.ordinal()).append(" (").append(chunk.chunkId()).append("):\n")
          .append(summary).append('\n');
    }
    return out.toString();
  }

  private static String reduceOne(ArtifactGenerationService.ModelGateway model, String subject, String input) {
    String prompt = "Reduce the complete source analysis for " + subject + " into a concise evidence-linked summary.\n"
        + "Preserve routes, symbols, data stores, dependencies, UI targets, risks, unknowns, and every referenced path.\n"
        + "Return facts only; do not invent behavior or omit an input section silently.\n" + input;
    String output = model.complete(prompt);
    if (output == null || output.isBlank()) throw new SourceReadException("SOURCE_SUMMARY_REDUCTION_EMPTY");
    return output.strip();
  }

  private static String join(Map<String, String> values) {
    var out = new StringBuilder();
    values.forEach((key, value) -> out.append("\n--- ").append(key).append(" ---\n").append(value));
    return out.toString();
  }

  private static String directory(String path) {
    int slash = path.indexOf('/');
    return slash < 0 ? "." : path.substring(0, slash);
  }
}
