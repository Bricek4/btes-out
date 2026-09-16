package studio.agent.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

record DocumentTemplate(String path, String markdown) {
  DocumentTemplate {
    if (path == null || !path.startsWith("docs/") || !path.toLowerCase().endsWith(".md")
        || path.startsWith("/") || path.contains("\\") || java.util.Arrays.asList(path.split("/")).contains("..")
        || markdown == null || markdown.isBlank()) {
      throw new IllegalArgumentException("document template must be a nonempty Markdown file under docs/");
    }
  }
}
record DocumentRequest(String title, String generatedContent, String sourceChangeSummary, String screenshots,
                       String manualSection) {
  DocumentRequest(String title, String content, String sourceChangeSummary, String screenshots) {
    this(title, content, sourceChangeSummary, screenshots, content);
  }
}
record RenderedArtifact(String path, String content) { }

final class DocumentRenderer {
  private static final String MANUAL_START = "<!-- agent-studio:manual:start -->";
  private static final String MANUAL_END = "<!-- agent-studio:manual:end -->";
  RenderedArtifact render(DocumentTemplate template, DocumentRequest request) {
    Objects.requireNonNull(request, "request is required");
    String content = template.markdown()
        .replace("{{title}}", safe(request.title()))
        .replace("{{content}}", safe(request.generatedContent()))
        .replace("{{screenshots}}", safe(request.screenshots()))
        .replace("{{sourceChangeSummary}}", safe(request.sourceChangeSummary()));
    int start = content.indexOf(MANUAL_START), end = content.indexOf(MANUAL_END);
    if ((start >= 0) != (end >= 0) || end >= 0 && end < start || start >= 0 && content.indexOf(MANUAL_START, start + 1) >= 0) {
      throw new IllegalArgumentException("manual section markers are unbalanced");
    }
    if (start >= 0) content = content.substring(0, start + MANUAL_START.length()) + safe(request.manualSection()) + content.substring(end);
    if (content.matches("(?s).*\\{\\{[^{}]+}}.*")) throw new IllegalArgumentException("template contains an unsupported placeholder");
    if (content.strip().isEmpty()) throw new IllegalArgumentException("rendered document is empty");
    ScreenshotMarkers.parseAll(content); return new RenderedArtifact(template.path(), content);
  }

  static String manualSection(String content) {
    if (content == null) return "";
    int start = content.indexOf(MANUAL_START), end = content.indexOf(MANUAL_END);
    if (start < 0 || end <= start || content.indexOf(MANUAL_START, start + 1) >= 0) return "";
    return content.substring(start + MANUAL_START.length(), end);
  }
  private String safe(String value) { return value == null ? "" : value; }
}

final class HtmlRenderer {
  private static final String VIEWPORT = "width=device-width, initial-scale=1";
  private static final String BASE_STYLE = "*{box-sizing:border-box}body{margin:0;padding:2rem;font:16px/1.6 system-ui,sans-serif;color:#172033;background:#f7f8fb}main{max-width:72rem;margin:auto}img{max-width:100%;height:auto}nav{display:flex;gap:1rem;flex-wrap:wrap}table{border-collapse:collapse;width:100%}th,td{padding:.6rem;border-bottom:1px solid #dfe3ea;text-align:left}";
  private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
      .allowElements("a", "article", "aside", "blockquote", "br", "button", "caption", "code", "dd", "details",
          "div", "dl", "dt", "em", "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
          "header", "hr", "img", "input", "label", "li", "main", "nav", "ol", "option", "p", "pre", "section",
          "select", "small", "span", "strong", "summary", "table", "tbody", "td", "th", "thead", "tr", "ul")
      .allowUrlProtocols("http", "https", "mailto", "tel", "artifact")
      .allowAttributes("class", "id", "title", "role", "aria-label", "aria-current", "colspan", "rowspan", "scope", "name", "type", "value", "disabled", "checked", "for")
      .globally()
      .allowAttributes("href").onElements("a")
      .allowAttributes("src").matching(HtmlRenderer::safeImageSource).onElements("img")
      .allowAttributes("alt", "width", "height").onElements("img")
      .allowAttributes("type", "name", "value", "checked", "disabled").onElements("input")
      .allowAttributes("for").onElements("label")
      .allowAttributes("open").onElements("details")
      .allowAttributes("value", "selected", "disabled").onElements("option")
      .requireRelNofollowOnLinks()
      .toFactory();

  String render(String template, String title) {
    if (template == null || template.isBlank()) throw new IllegalArgumentException("HTML template is required");
    String titled = template.replace("{{title}}", title == null ? "" : title);
    MarkerContent markerContent = preserveMarkers(titled);
    Document parsed = Jsoup.parse(markerContent.content());
    String safeBody = POLICY.sanitize(parsed.body().html());
    Document output = Jsoup.parse("<!doctype html><html><head></head><body></body></html>");
    output.outputSettings().prettyPrint(false);
    output.title(title == null ? "" : title);
    output.head().appendElement("meta").attr("name", "viewport").attr("content", VIEWPORT);
    output.head().appendElement("style").attr("data-agent-studio", "base-style").text(BASE_STYLE);
    output.body().html(safeBody);
    String result = output.outerHtml();
    for (var marker : markerContent.replacements().entrySet()) result = result.replace(marker.getKey(), marker.getValue());
    if (result.strip().isEmpty() || Jsoup.parse(result).body().text().isBlank()) throw new IllegalArgumentException("rendered HTML is empty");
    return result;
  }

  HtmlValidationReport validate(String html, Set<String> availableAssets) {
    if (html == null || html.isBlank()) return new HtmlValidationReport(false, List.of("HTML_EMPTY"));
    Document document = Jsoup.parse(html);
    List<String> issues = new ArrayList<>();
    if (document.selectFirst("html") == null || document.head() == null || document.body() == null) issues.add("HTML_STRUCTURE_INVALID");
    if (document.body().text().isBlank() && document.select("img[src]").isEmpty()) issues.add("HTML_BODY_EMPTY");
    Set<String> known = availableAssets == null ? Set.of() : Set.copyOf(availableAssets);
    for (Element element : document.select("img[src], source[src], link[href], script[src]")) {
      String attribute = element.hasAttr("src") ? "src" : "href";
      String reference = element.attr(attribute).trim();
      if (reference.isEmpty()) { issues.add("ASSET_REFERENCE_EMPTY"); continue; }
      if (reference.startsWith("#") || hasExternalScheme(reference) || reference.startsWith("artifact://")) continue;
      if (reference.startsWith("//") || reference.contains("..") || !known.contains(reference)) {
        issues.add("ASSET_REFERENCE_INVALID:" + safeIssuePath(reference));
      }
    }
    for (Element anchor : document.select("a[href^=#]")) {
      String id = anchor.attr("href").substring(1);
      if (!id.isBlank() && document.getElementById(id) == null) issues.add("ANCHOR_TARGET_MISSING:" + safeIssuePath(id));
    }
    return new HtmlValidationReport(issues.isEmpty(), List.copyOf(issues));
  }

  private static boolean safeImageSource(String value) {
    if (value == null || value.isBlank() || value.startsWith("//")) return false;
    if (value.startsWith("artifact://")) return true;
    return !hasExternalScheme(value) && !value.contains("..") && !value.startsWith("/");
  }

  private static boolean hasExternalScheme(String value) {
    try { return java.net.URI.create(value).getScheme() != null; }
    catch (RuntimeException invalid) { return true; }
  }

  private static String safeIssuePath(String value) {
    return value.replaceAll("[^A-Za-z0-9_./-]", "?").substring(0, Math.min(120, value.length()));
  }

  private static MarkerContent preserveMarkers(String html) {
    var markers = ScreenshotMarkers.parseAll(html);
    if (markers.isEmpty()) return new MarkerContent(html, Map.of());
    String value = html;
    var replacements = new java.util.LinkedHashMap<String, String>();
    for (ScreenshotMarker marker : markers) {
      String token = "AGENT_SCREENSHOT_MARKER_" + java.util.UUID.randomUUID().toString().replace("-", "");
      if (occurrences(value, marker.raw()) != 1) throw new IllegalArgumentException("screenshot marker replacement is ambiguous");
      value = value.replace(marker.raw(), token);
      replacements.put(token, marker.raw());
    }
    return new MarkerContent(value, Map.copyOf(replacements));
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int start = 0; (start = text.indexOf(needle, start)) >= 0; start += needle.length()) count++;
    return count;
  }

  private record MarkerContent(String content, Map<String, String> replacements) { }
}

record HtmlValidationReport(boolean valid, List<String> issues) { }
