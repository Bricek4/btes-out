package studio.agent.platform.artifact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ArtifactTreeBuilder {
  private ArtifactTreeBuilder() { }

  static List<Node> build(List<Entry> entries) {
    var root = new Branch("", "");
    for (var entry : entries) {
      var segments = segments(entry.name());
      var branch = root;
      for (int index = 0; index < segments.length - 1; index++) {
        String segment = segments[index];
        String path = branch.path.isEmpty() ? segment : branch.path + "/" + segment;
        if (branch.files.containsKey(segment)) throw new IllegalArgumentException("artifact path conflicts with a file");
        branch = branch.folders.computeIfAbsent(segment, ignored -> new Branch(segment, path));
      }
      String filename = segments[segments.length - 1];
      if (branch.folders.containsKey(filename) || branch.files.putIfAbsent(filename, entry) != null) {
        throw new IllegalArgumentException("duplicate artifact path");
      }
    }
    return nodes(root);
  }

  private static List<Node> nodes(Branch branch) {
    var result = new ArrayList<Node>();
    branch.folders.values().stream().sorted(Comparator.comparing(folder -> folder.name))
        .forEach(folder -> result.add(new Node(folder.name, folder.path, "FOLDER", null, null, 0, null, 0, null, nodes(folder))));
    branch.files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
      var entry = file.getValue();
      String path = branch.path.isEmpty() ? file.getKey() : branch.path + "/" + file.getKey();
      result.add(new Node(file.getKey(), path, "ARTIFACT", entry.artifactId(), entry.kind(), entry.version(),
          entry.mediaType(), entry.sizeBytes(), entry.sha256(), List.of()));
    });
    return List.copyOf(result);
  }

  private static String[] segments(String path) {
    if (path == null || path.isBlank() || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("invalid artifact path");
    }
    var segments = path.split("/", -1);
    for (var segment : segments) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
          || segment.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("invalid artifact path");
      }
    }
    return segments;
  }

  record Entry(UUID artifactId, String name, String kind, int version, String mediaType, long sizeBytes, String sha256) { }

  record Node(String name, String path, String type, UUID artifactId, String kind, int version,
              String mediaType, long sizeBytes, String sha256, List<Node> children) { }

  private static final class Branch {
    private final String name;
    private final String path;
    private final Map<String, Branch> folders = new LinkedHashMap<>();
    private final Map<String, Entry> files = new LinkedHashMap<>();

    private Branch(String name, String path) {
      this.name = name;
      this.path = path;
    }
  }
}
