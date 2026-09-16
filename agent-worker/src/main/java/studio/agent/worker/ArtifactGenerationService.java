package studio.agent.worker;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import studio.agent.contracts.TaskType;

/** Generates transient model output and validates it against the selected immutable template. */
public final class ArtifactGenerationService {
  private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
      "(?im)(\\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token|authorization)\\b\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
  private static final int MAX_EVIDENCE_CHARS = 250_000;
  private static final int MAX_OUTPUT_CHARS = 2_000_000;

  private final ModelGateway model;
  private final DocumentRenderer documents = new DocumentRenderer();
  private final HtmlRenderer html = new HtmlRenderer();

  public ArtifactGenerationService(ModelGateway model) { this.model = Objects.requireNonNull(model); }

  public GeneratedArtifact generate(TaskType type, String sourceEvidence, String template, String changeSummary) {
    String defaultPath = type == TaskType.HTML ? "site/index.html"
        : type == TaskType.USER_GUIDE ? "docs/user-guide.md" : "docs/README.md";
    String format = type == TaskType.HTML ? "html" : "markdown";
    return generate(new GenerationRequest(type, sourceEvidence, defaultPath, format, template,
        "Generated project artifact", Map.of(), changeSummary, "", Set.of()));
  }

  public GeneratedArtifact generate(GenerationRequest request) {
    Objects.requireNonNull(request, "generation request is required");
    validateRequest(request);
    String evidence = redactAndLimit(request.sourceEvidence());
    String existing = redactAndLimit(request.existingContent());
    String prompt = buildPrompt(request, evidence, existing);
    String generated = model.complete(prompt);
    if (generated == null || generated.isBlank()) throw new IllegalArgumentException("model returned empty artifact");
    if (generated.length() > MAX_OUTPUT_CHARS) throw new IllegalArgumentException("model output exceeds the artifact size limit");

    if (request.type() == TaskType.HTML) {
      String title = request.title().isBlank() ? "Project" : request.title();
      String templated = applyHtmlTemplate(request.templateBody(), title, generated);
      String rendered = html.render(templated, title);
      validateMarkerProfiles(rendered, request.allowedLoginProfileRefs());
      HtmlValidationReport report = html.validate(rendered, request.availableAssets());
      return new GeneratedArtifact(request.outputPath(), rendered, safeSummary(request.sourceChangeSummary()), report);
    }

    String manualContent = DocumentRenderer.manualSection(request.existingContent());
    var documentTemplate = new DocumentTemplate(request.outputPath(), request.templateBody());
    var rendered = documents.render(documentTemplate, new DocumentRequest(request.title(), generated,
        request.sourceChangeSummary(), "", manualContent));
    validateMarkerProfiles(rendered.content(), request.allowedLoginProfileRefs());
    if (rendered.content().isBlank()) throw new IllegalArgumentException("required document artifact is empty");
    return new GeneratedArtifact(rendered.path(), rendered.content(), safeSummary(request.sourceChangeSummary()),
        new HtmlValidationReport(true, List.of()));
  }

  private static void validateRequest(GenerationRequest request) {
    if (request.type() == TaskType.SCREENSHOT) throw new IllegalArgumentException("screenshot tasks use a marker manifest, not document generation");
    if (request.sourceEvidence() == null || request.sourceEvidence().isBlank()) throw new IllegalArgumentException("source evidence is required");
    if (request.templateBody() == null || request.templateBody().isBlank()) throw new IllegalArgumentException("template body is required");
    if (request.outputPath() == null || request.outputPath().isBlank() || request.outputPath().startsWith("/")
        || request.outputPath().contains("\\") || java.util.Arrays.asList(request.outputPath().split("/")).contains("..")) {
      throw new IllegalArgumentException("template output path is invalid");
    }
    if (request.type() == TaskType.HTML) {
      if (!"html".equalsIgnoreCase(request.outputFormat()) || !request.outputPath().toLowerCase().endsWith(".html")) {
        throw new IllegalArgumentException("HTML tasks require an HTML template and .html output path");
      }
    } else if (!(request.outputPath().startsWith("docs/") && request.outputPath().toLowerCase().endsWith(".md"))
        || !Set.of("markdown", "md").contains(request.outputFormat().toLowerCase())) {
      throw new IllegalArgumentException("documentation tasks require a Markdown template under docs/");
    }
    if (request.existingContent() != null && request.existingContent().length() > MAX_OUTPUT_CHARS) {
      throw new IllegalArgumentException("existing artifact exceeds the size limit");
    }
  }

  private static String buildPrompt(GenerationRequest request, String evidence, String existing) {
    var out = new StringBuilder(4096);
    out.append("Create a ").append(request.type()).append(" artifact using the selected template.\n")
        .append("Use only facts present in the source evidence; do not invent product behavior or credentials.\n")
        .append("Return only the artifact body. Do not include commentary, code fences, scripts, or executable HTML.\n")
        .append("For Markdown, include screenshot marker comments only for pages that can be reached using supplied route evidence. Each marker must use the versioned agent-studio:screenshot:v1 JSON shape with a unique id, an allowed loginProfileRef, target, menuPath/actions when known, and caption.\n")
        .append("Task goal: ").append(request.title()).append('\n')
        .append("Output format: ").append(request.outputFormat()).append("\nOutput path: ").append(request.outputPath()).append('\n')
        .append("Template:\n").append(request.templateBody()).append("\n")
        .append("Task parameters:\n").append(json(request.parameters())).append("\n")
        .append("Allowed login profile references:\n").append(String.join(",", request.allowedLoginProfileRefs())).append("\n")
        .append("Source-change summary:\n").append(safeSummary(request.sourceChangeSummary())).append("\n")
        .append("Source evidence:\n").append(evidence).append("\n");
    if (request.incremental() && !existing.isBlank()) {
      out.append("Existing artifact for an incremental update:\n").append(existing).append("\n")
          .append("Preserve unaffected sections and any agent-studio:manual block exactly. Apply only changes described in the source-change summary.\n");
    }
    return out.toString();
  }

  private static String applyHtmlTemplate(String template, String title, String generated) {
    String body = template.replace("{{title}}", title);
    if (body.contains("{{content}}")) body = body.replace("{{content}}", generated);
    else {
      var document = org.jsoup.Jsoup.parse(body);
      var generatedFragment = org.jsoup.Jsoup.parseBodyFragment(generated);
      document.body().appendChildren(generatedFragment.body().childNodesCopy());
      body = document.outerHtml();
    }
    if (body.matches("(?s).*\\{\\{[^{}]+}}.*")) throw new IllegalArgumentException("HTML template contains an unsupported placeholder");
    return body;
  }

  private static String redactAndLimit(String value) {
    if (value == null) return "";
    String redacted = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1[REDACTED]");
    if (redacted.length() > MAX_EVIDENCE_CHARS) return redacted.substring(0, MAX_EVIDENCE_CHARS) + "\n[additional evidence omitted]";
    return redacted;
  }

  private static String safeSummary(String value) { return value == null ? "" : redactAndLimit(value); }

  private static void validateMarkerProfiles(String content, Set<String> allowedLoginProfileRefs) {
    for (ScreenshotMarker marker : ScreenshotMarkers.parseAll(content)) {
      if (!allowedLoginProfileRefs.contains(marker.loginProfileRef())) {
        throw new IllegalArgumentException("screenshot marker references an unavailable login profile");
      }
    }
  }

  private static String json(Map<String, Object> value) {
    try { return new tools.jackson.databind.ObjectMapper().writeValueAsString(value == null ? Map.of() : value); }
    catch (tools.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("task parameters are invalid"); }
  }

  public interface ModelGateway { String complete(String prompt); }

  public record GenerationRequest(TaskType type, String sourceEvidence, String outputPath,
      String outputFormat, String templateBody, String title, Map<String, Object> parameters,
      String sourceChangeSummary, String existingContent, Set<String> allowedLoginProfileRefs,
      Set<String> availableAssets, boolean incremental) {
    public GenerationRequest {
      parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
      allowedLoginProfileRefs = Set.copyOf(allowedLoginProfileRefs == null ? Set.of() : allowedLoginProfileRefs);
      availableAssets = Set.copyOf(availableAssets == null ? Set.of() : availableAssets);
      title = title == null ? "" : title;
      outputFormat = outputFormat == null ? "" : outputFormat;
      sourceChangeSummary = sourceChangeSummary == null ? "" : sourceChangeSummary;
      existingContent = existingContent == null ? "" : existingContent;
    }

    public GenerationRequest(TaskType type, String sourceEvidence, String outputPath,
        String outputFormat, String templateBody, String title, Map<String, Object> parameters,
        String sourceChangeSummary, String existingContent, Set<String> allowedLoginProfileRefs) {
      this(type, sourceEvidence, outputPath, outputFormat, templateBody, title, parameters,
          sourceChangeSummary, existingContent, allowedLoginProfileRefs, Set.of(), false);
    }
  }
}

record GeneratedArtifact(String path, String content, String sourceChangeSummary,
                         HtmlValidationReport validationReport) { }
