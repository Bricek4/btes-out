package studio.agent.worker;

import java.util.List;
import java.util.UUID;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

/** A bounded LangGraph4j subflow: inspect snapshot, execute declared path, capture, then replace. */
public final class ScreenshotResolver {
  private final BrowserWorkerClient browser;
  private final StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
  public ScreenshotResolver(BrowserWorkerClient browser) { this.browser = browser; }
  public ScreenshotResolution resolve(UUID taskId, String baseUrl, String content, List<String> sourceEvidence) {
    List<ScreenshotMarker> markers = ScreenshotMarkers.parseAll(content);
    String rendered = content;
    for (ScreenshotMarker marker : markers) {
      if (!sourceEvidence.containsAll(marker.menuPath())) return ScreenshotResolution.approval(rendered, marker.id(), "approval://screenshot-route/" + marker.id());
      String session = browser.open(taskId, baseUrl, marker.loginProfileRef());
      try {
        browser.login(session); BrowserSnapshot snapshot = browser.snapshot(session);
        if (snapshot.accessibilityText() == null || !snapshot.accessibilityText().contains(marker.target())) return ScreenshotResolution.failed(rendered, marker.id());
        for (String menu : marker.menuPath()) browser.click(session, menu);
        for (String action : marker.actions()) browser.click(session, action);
        BrowserSnapshot reached = browser.snapshot(session);
        if (reached.url() == null || reached.url().isBlank()) return ScreenshotResolution.failed(rendered, marker.id());
        String artifact = browser.screenshot(session, marker.id());
        if (artifact == null || artifact.isBlank()) return ScreenshotResolution.failed(rendered, marker.id());
        rendered = rendered.replace(marker.raw(), "![" + marker.caption() + "](" + artifact + ")");
      } finally { browser.close(session); }
    }
    return rendered.contains("agent-studio:screenshot") ? ScreenshotResolution.failed(rendered, "leftover-marker") : ScreenshotResolution.complete(rendered);
  }
  public StateGraph<AgentState> graph() { return graph; }
}
record ScreenshotResolution(String content, boolean completed, String failedMarkerId, String approvalReference) {
  static ScreenshotResolution complete(String content) { return new ScreenshotResolution(content, true, null, null); }
  static ScreenshotResolution failed(String content, String id) { return new ScreenshotResolution(content, false, id, null); }
  static ScreenshotResolution approval(String content, String id, String ref) { return new ScreenshotResolution(content, false, id, ref); }
}
