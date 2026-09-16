package studio.agent.worker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ScreenshotMarkers {
  private static final String PREFIX = "<!-- agent-studio:screenshot:";
  private static final Pattern MARKER = Pattern.compile("<!-- agent-studio:screenshot:v1\\s+(\\{.*?})\\s*-->", Pattern.DOTALL);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> FIELDS = Set.of("id", "loginProfileRef", "target", "routePath", "menuPath", "actions", "caption");
  private static final int MAX_MARKERS = 100;
  private ScreenshotMarkers() { }
  public static List<ScreenshotMarker> parseAll(String content) {
    if (content == null) throw new IllegalArgumentException("content is required");
    List<ScreenshotMarker> markers = new ArrayList<>(); Set<String> ids = new HashSet<>(); Matcher match = MARKER.matcher(content);
    while (match.find()) {
      try {
        if (markers.size() >= MAX_MARKERS) throw new IllegalArgumentException("too many screenshot markers");
        JsonNode node = JSON.readTree(match.group(1));
        if (!node.isObject()) throw new IllegalArgumentException("marker must be a JSON object");
        for (var property : node.properties()) if (!FIELDS.contains(property.getKey())) throw new IllegalArgumentException("unknown marker field");
        String id = boundedText(node, "id", "[A-Za-z0-9][A-Za-z0-9_-]{0,63}", 64);
        String profile = boundedText(node, "loginProfileRef", "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", 128);
        String target = boundedText(node, "target", null, 240);
        String route = optionalText(node, "routePath", 512);
        var menuPath = strings(node, "menuPath", 6);
        var actions = actions(node, "actions", 10);
        String caption = boundedText(node, "caption", null, 240);
        var marker = new ScreenshotMarker(match.group(), id, profile, target, menuPath, actions, route, caption);
        if (!ids.add(marker.id())) throw new IllegalArgumentException("duplicate screenshot marker id: " + marker.id());
        markers.add(marker);
      } catch (Exception failure) { throw new IllegalArgumentException("invalid screenshot marker", failure); }
    }
    if (content.contains(PREFIX) && markers.size() != occurrences(content, PREFIX)) {
      throw new IllegalArgumentException("malformed or unsupported screenshot marker");
    }
    return List.copyOf(markers);
  }
  private static String boundedText(JsonNode node, String name, String regex, int maxLength) {
    String value = optionalText(node, name, maxLength);
    if (value == null || (regex != null && !value.matches(regex))) throw new IllegalArgumentException(name + " is invalid");
    return value;
  }
  private static String optionalText(JsonNode node, String name, int maxLength) {
    var value = node.get(name);
    if (value == null || value.isNull()) return null;
    if (!value.isString() || value.asString().isBlank() || value.asString().length() > maxLength) throw new IllegalArgumentException(name + " is invalid");
    return value.asString().trim();
  }
  private static List<String> strings(JsonNode node, String name, int maxCount) {
    var value = node.get(name); if (value == null) return List.of();
    if (!value.isArray() || value.size() > maxCount) throw new IllegalArgumentException(name + " must be a bounded array");
    var result = new ArrayList<String>();
    for (JsonNode item : value) {
      if (!item.isString() || item.asString().isBlank() || item.asString().length() > 120) throw new IllegalArgumentException(name + " contains invalid item");
      result.add(item.asString().trim());
    }
    return List.copyOf(result);
  }
  private static List<ScreenshotAction> actions(JsonNode node, String name, int maxCount) {
    var value = node.get(name);
    if (value == null) return List.of();
    if (!value.isArray() || value.size() > maxCount) throw new IllegalArgumentException(name + " must be a bounded array");
    var result = new ArrayList<ScreenshotAction>();
    for (JsonNode item : value) {
      if (!item.isObject()) throw new IllegalArgumentException(name + " contains invalid item");
      for (var property : item.properties()) {
        if (!Set.of("type", "locator", "value").contains(property.getKey())) throw new IllegalArgumentException("unknown screenshot action field");
      }
      String type = boundedText(item, "type", "click|fill|select|check|uncheck|wait", 16);
      JsonNode locator = item.get("locator");
      if (locator == null || !locator.isObject()) throw new IllegalArgumentException("action locator is required");
      for (var property : locator.properties()) {
        if (!Set.of("kind", "role", "name").contains(property.getKey())) throw new IllegalArgumentException("unknown semantic locator field");
      }
      String kind = boundedText(locator, "kind", "role|label|test-id", 16);
      String role = optionalText(locator, "role", 80);
      String locatorName = boundedText(locator, "name", null, 160);
      String actionValue = optionalText(item, "value", 500);
      result.add(new ScreenshotAction(type, new SemanticLocator(kind, role, locatorName), actionValue));
    }
    return List.copyOf(result);
  }
  private static int occurrences(String content, String needle) {
    int result = 0;
    for (int start = 0; (start = content.indexOf(needle, start)) >= 0; start += needle.length()) result++;
    return result;
  }
}
