package studio.agent.worker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Renders small evidence-derived diagrams without executing or embedding arbitrary markup. */
public final class DiagramRenderer {
  private DiagramRenderer() { }

  static List<DiagramArtifact> render(SourceReadResult read) {
    var labels = new LinkedHashSet<String>();
    read.facts().values().forEach(facts -> {
      if (!facts.filePath().isBlank()) labels.add(facts.filePath());
      labels.addAll(facts.routes());
      labels.addAll(facts.dataStores());
    });
    var values = labels.stream().filter(value -> !value.isBlank()).limit(12).toList();
    return List.of(
        new DiagramArtifact("diagrams/system-overview.svg", svg("System overview", values)),
        new DiagramArtifact("diagrams/data-flow.svg", svg("Data flow", read.facts().values().stream()
            .flatMap(value -> value.dataStores().stream()).distinct().limit(12).toList())),
        new DiagramArtifact("diagrams/deployment-topology.svg", svg("Deployment topology", read.facts().values().stream()
            .flatMap(value -> value.dependencies().stream()).distinct().limit(12).toList())));
  }

  private static String svg(String title, List<String> labels) {
    int width = 960;
    int rowHeight = 48;
    int height = Math.max(160, 80 + Math.max(1, labels.size()) * rowHeight);
    var out = new StringBuilder();
    out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
        .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ')
        .append(height).append("\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
        .append("<text x=\"32\" y=\"42\" font-family=\"system-ui,sans-serif\" font-size=\"22\" font-weight=\"700\" fill=\"#172033\">")
        .append(escape(title)).append("</text>");
    if (labels.isEmpty()) labels = List.of("No evidence-derived nodes");
    for (int index = 0; index < labels.size(); index++) {
      int y = 62 + index * rowHeight;
      if (index > 0) out.append("<path d=\"M480 ").append(y - 14).append("V").append(y)
          .append("\" stroke=\"#8a96a8\" stroke-width=\"2\"/>");
      out.append("<rect x=\"160\" y=\"").append(y).append("\" width=\"640\" height=\"32\" rx=\"8\" fill=\"#eef3ff\" stroke=\"#8aa7e8\"/>")
          .append("<text x=\"480\" y=\"").append(y + 21).append("\" text-anchor=\"middle\" font-family=\"system-ui,sans-serif\" font-size=\"14\" fill=\"#172033\">")
          .append(escape(labels.get(index))).append("</text>");
    }
    return out.append("</svg>").toString();
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;");
  }
}

record DiagramArtifact(String path, String content) { }
