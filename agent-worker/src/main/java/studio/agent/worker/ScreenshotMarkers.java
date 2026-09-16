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
  private static final Pattern MARKER = Pattern.compile("<!-- agent-studio:screenshot:v1 (\\{.*?}) -->", Pattern.DOTALL);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> FIELDS = Set.of("id", "loginProfileRef", "target", "menuPath", "actions", "caption");
  private ScreenshotMarkers() { }
  public static List<ScreenshotMarker> parseAll(String content) {
    if (content == null) throw new IllegalArgumentException("content is required");
    List<ScreenshotMarker> markers = new ArrayList<>(); Set<String> ids = new HashSet<>(); Matcher match = MARKER.matcher(content);
    while (match.find()) {
      try {
        JsonNode node = JSON.readTree(match.group(1));
        for (String name : node.propertyNames()) if (!FIELDS.contains(name)) throw new IllegalArgumentException("unknown marker field");
        var marker = new ScreenshotMarker(match.group(), text(node, "id"), text(node, "loginProfileRef"), text(node, "target"), strings(node, "menuPath"), strings(node, "actions"), text(node, "caption"));
        if (!ids.add(marker.id())) throw new IllegalArgumentException("duplicate screenshot marker id: " + marker.id());
        markers.add(marker);
      } catch (Exception failure) { throw new IllegalArgumentException("invalid screenshot marker", failure); }
    }
    return List.copyOf(markers);
  }
  private static String text(JsonNode node, String name) { var value = node.get(name); if (value == null || !value.isTextual() || value.asString().isBlank()) throw new IllegalArgumentException(name + " is required"); return value.asString(); }
  private static List<String> strings(JsonNode node, String name) { var value = node.get(name); if (value == null) return List.of(); if (!value.isArray()) throw new IllegalArgumentException(name + " must be an array"); var result = new ArrayList<String>(); for (JsonNode item : value) { if (!item.isTextual() || item.asString().isBlank()) throw new IllegalArgumentException(name + " contains invalid item"); result.add(item.asString()); } return List.copyOf(result); }
}
