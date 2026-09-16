package studio.agent.worker;

import java.util.Objects;
import java.util.regex.Pattern;

record DocumentTemplate(String path, String markdown) { DocumentTemplate { if (path == null || !path.startsWith("docs/") || markdown == null || markdown.isBlank()) throw new IllegalArgumentException("document template must be a nonempty docs path"); } }
record DocumentRequest(String title, String manualContent, String sourceChangeSummary, String screenshots) { }
record RenderedArtifact(String path, String content) { }

final class DocumentRenderer {
  private static final String MANUAL_START = "<!-- agent-studio:manual:start -->";
  private static final String MANUAL_END = "<!-- agent-studio:manual:end -->";
  RenderedArtifact render(DocumentTemplate template, DocumentRequest request) {
    Objects.requireNonNull(request, "request is required");
    String content = template.markdown().replace("{{title}}", safe(request.title())).replace("{{screenshots}}", safe(request.screenshots()));
    int start = content.indexOf(MANUAL_START), end = content.indexOf(MANUAL_END);
    if (start >= 0 && end > start) content = content.substring(0, start + MANUAL_START.length()) + safe(request.manualContent()) + content.substring(end);
    if (!safe(request.sourceChangeSummary()).isBlank()) content += "\n\n## Source changes\n" + safe(request.sourceChangeSummary());
    if (content.strip().isEmpty()) throw new IllegalArgumentException("rendered document is empty");
    ScreenshotMarkers.parseAll(content); return new RenderedArtifact(template.path(), content);
  }
  private String safe(String value) { return value == null ? "" : value; }
}

final class HtmlRenderer {
  private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>|<script[^>]*/>");
  private static final Pattern EVENTS = Pattern.compile("(?i)\\s+on[a-z]+\\s*=\\s*(['\"]).*?\\1");
  private static final Pattern JS_URL = Pattern.compile("(?i)javascript:");
  String render(String template, String title) {
    if (template == null || template.isBlank()) throw new IllegalArgumentException("HTML template is required");
    String safe = JS_URL.matcher(EVENTS.matcher(SCRIPT.matcher(template).replaceAll("")).replaceAll("")).replaceAll("");
    safe = safe.replace("{{title}}", title == null ? "" : title);
    if (!safe.toLowerCase().contains("<html")) safe = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>" + safe + "</body></html>";
    if (safe.strip().isEmpty()) throw new IllegalArgumentException("rendered HTML is empty");
    return safe;
  }
}
