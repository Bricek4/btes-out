package studio.agent.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import studio.agent.contracts.TaskType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds a reference-style document set from evidence-derived facts and summaries. */
public final class DocumentPackageGenerator {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<SectionSpec> SECTIONS = List.of(
      new SectionSpec("docs/01-product-scope.md", "Product scope, users, goals, non-goals, and evidence status"),
      new SectionSpec("docs/02-core-flows.md", "End-to-end workflows, state transitions, and failure branches"),
      new SectionSpec("docs/03-system-architecture.md", "System context, service boundaries, interfaces, and deployment topology"),
      new SectionSpec("docs/04-data-and-storage.md", "Core entities, ownership, persistence, and data lifecycle"),
      new SectionSpec("docs/05-integrations-and-runtime.md", "External dependencies, configuration, observability, and operations"),
      new SectionSpec("docs/06-implementation-guide.md", "Implementation order, invariants, verification, and known gaps"));

  private DocumentPackageGenerator() { }

  public static DocumentPackage generate(ArtifactGenerationService.GenerationRequest request,
      SourceReadResult read, ArtifactGenerationService.ModelGateway model) {
    if (request == null || request.type() != TaskType.PROJECT_DOCS) {
      throw new IllegalArgumentException("document package requires a project documentation request");
    }
    if (read == null || !read.complete()) throw new SourceReadException("SOURCE_READ_INCOMPLETE");
    return generate(request, read, model, SourceReadReducer.reduce(read, model));
  }

  static DocumentPackage generate(ArtifactGenerationService.GenerationRequest request,
      SourceReadResult read, ArtifactGenerationService.ModelGateway model, String context) {
    if (request == null || request.type() != TaskType.PROJECT_DOCS) {
      throw new IllegalArgumentException("document package requires a project documentation request");
    }
    if (read == null || !read.complete()) throw new SourceReadException("SOURCE_READ_INCOMPLETE");
    var documents = new ArrayList<GeneratedArtifact>();
    var rootRequest = new ArtifactGenerationService.GenerationRequest(request.type(), context, request.outputPath(),
        request.outputFormat(), request.templateBody(), request.title(), request.parameters(), request.sourceChangeSummary(),
        request.existingContent(), request.allowedLoginProfileRefs(), request.availableAssets(), request.incremental());
    var root = new ArtifactGenerationService(model).generateFromSynthesis(rootRequest, context);
    documents.add(root);
    for (SectionSpec section : SECTIONS) {
      documents.add(new GeneratedArtifact(section.path(), sectionBody(model, request, section, context),
          request.sourceChangeSummary(), new HtmlValidationReport(true, List.of())));
    }
    for (String module : modules(read)) {
      String path = "docs/modules/" + safeSlug(module) + ".md";
      String body = model.complete(sectionPrompt(request, "Module " + module,
          "Describe this module's responsibilities, inputs, outputs, dependencies, routes, data stores, and boundaries", context));
      documents.add(new GeneratedArtifact(path, normalizeBody(body, module), request.sourceChangeSummary(),
          new HtmlValidationReport(true, List.of())));
    }
    var diagrams = DiagramRenderer.render(read);
    return new DocumentPackage(List.copyOf(documents), diagrams, documentMapJson(documents, diagrams));
  }

  private static String sectionBody(ArtifactGenerationService.ModelGateway model,
      ArtifactGenerationService.GenerationRequest request, SectionSpec section, String context) {
    String body = model.complete(sectionPrompt(request, section.path(), section.focus(), context));
    return normalizeBody(body, section.focus());
  }

  private static String sectionPrompt(ArtifactGenerationService.GenerationRequest request,
      String section, String focus, String context) {
    return "Write the Markdown section for " + section + ".\n"
        + "Focus: " + focus + ".\n"
        + "Use only the supplied evidence summaries. Label unknowns. Do not use project names, routes, modules, or examples that are absent from evidence.\n"
        + "Add screenshot markers only when a real route, semantic control, and allowed login profile are present.\n"
        + "Task audience: " + request.title() + "\nEvidence summaries:\n" + context;
  }

  private static String normalizeBody(String body, String fallbackHeading) {
    if (body == null || body.isBlank()) throw new SourceReadException("DOCUMENT_SECTION_EMPTY");
    String value = body.strip();
    if (value.startsWith("```") && value.endsWith("```")) {
      int newline = value.indexOf('\n');
      if (newline >= 0) value = value.substring(newline + 1, value.length() - 3).strip();
    }
    if (value.matches("(?s).*\\{\\{[^{}]+}}.*")) throw new SourceReadException("DOCUMENT_SECTION_PLACEHOLDER_UNRESOLVED");
    if (!value.startsWith("#")) value = "# " + fallbackHeading + "\n\n" + value;
    return value;
  }

  private static List<String> modules(SourceReadResult read) {
    var values = new LinkedHashSet<String>();
    for (SourceReadFile file : read.plan().files()) {
      if (file.status() == SourceReadStatus.SKIPPED) continue;
      int slash = file.path().indexOf('/');
      values.add(slash < 0 ? "root" : file.path().substring(0, slash));
    }
    return values.stream().sorted(Comparator.comparing(String::toLowerCase)).toList();
  }

  private static String safeSlug(String value) {
    String slug = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
        .replaceAll("^-+|-+$", "");
    return slug.isBlank() ? "module" : slug.substring(0, Math.min(100, slug.length()));
  }

  private static String documentMapJson(List<GeneratedArtifact> documents, List<DiagramArtifact> diagrams) {
    var map = new LinkedHashMap<String, Object>();
    map.put("documents", documents.stream().map(GeneratedArtifact::path).toList());
    map.put("diagrams", diagrams.stream().map(DiagramArtifact::path).toList());
    map.put("coverage", "manifests/source-read-coverage.json");
    map.put("screenshotCoverage", "manifests/screenshot-coverage.json");
    try { return JSON.writeValueAsString(map); }
    catch (JacksonException invalid) { throw new SourceReadException("DOCUMENT_MAP_INVALID", invalid); }
  }
}

record DocumentPackage(List<GeneratedArtifact> documents, List<DiagramArtifact> diagrams,
                       String documentMapJson) {
  DocumentPackage {
    documents = List.copyOf(documents == null ? List.of() : documents);
    diagrams = List.copyOf(diagrams == null ? List.of() : diagrams);
  }
}

record SectionSpec(String path, String focus) { }
