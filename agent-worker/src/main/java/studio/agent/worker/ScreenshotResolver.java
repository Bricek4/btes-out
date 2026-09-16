package studio.agent.worker;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

/** A bounded graph which validates evidence, executes declared paths, and verifies replacements. */
public final class ScreenshotResolver {
  private static final String INPUT = "input";
  private static final String MARKERS = "markers";
  private static final String RENDERED = "rendered";
  private static final String RESOLUTION = "resolution";

  private final BrowserWorkerClient browser;
  private final StateGraph<AgentState> graph;
  private final CompiledGraph<AgentState> compiledGraph;

  public ScreenshotResolver(BrowserWorkerClient browser) {
    this.browser = java.util.Objects.requireNonNull(browser, "browser is required");
    try {
      graph = new StateGraph<>(AgentState::new)
          .addNode("preflight", this::preflight)
          .addNode("capture", this::capture)
          .addNode("verify", this::verify)
          .addEdge(START, "preflight")
          .addEdge("preflight", "capture")
          .addEdge("capture", "verify")
          .addEdge("verify", END);
      compiledGraph = graph.compile();
    } catch (GraphStateException failure) {
      throw new IllegalStateException("screenshot graph could not be compiled", failure);
    }
  }

  public ScreenshotResolution resolve(UUID taskId, String baseUrl, String content,
      List<String> sourceEvidence, Set<String> allowedLoginProfileRefs) {
    if (taskId == null || baseUrl == null || baseUrl.isBlank() || content == null) {
      throw new IllegalArgumentException("task, base URL and content are required");
    }
    var input = new ResolutionInput(taskId, baseUrl, content,
        sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence),
        allowedLoginProfileRefs == null ? Set.of() : Set.copyOf(allowedLoginProfileRefs));
    try {
      AgentState result = compiledGraph.invoke(Map.of(INPUT, input)).orElseThrow(
          () -> new IllegalStateException("screenshot graph returned no result"));
      return (ScreenshotResolution) result.value(RESOLUTION).orElseThrow(
          () -> new IllegalStateException("screenshot graph omitted its result"));
    } catch (RuntimeException failure) {
      return ScreenshotResolution.failed(content, "screenshot-resolution", "SCREENSHOT_RESOLUTION_FAILED");
    }
  }

  public CompiledGraph<AgentState> graph() { return compiledGraph; }

  private CompletableFuture<Map<String, Object>> preflight(AgentState state) {
    ResolutionInput input = input(state);
    try {
      List<ScreenshotMarker> markers = ScreenshotMarkers.parseAll(input.content());
      for (ScreenshotMarker marker : markers) {
        if (!input.allowedLoginProfileRefs().contains(marker.loginProfileRef())) {
          return completed(RESOLUTION, ScreenshotResolution.failed(input.content(), marker.id(), "LOGIN_PROFILE_UNAVAILABLE"));
        }
        if (!input.sourceEvidence().containsAll(marker.menuPath())
            || !input.sourceEvidence().containsAll(marker.actions().stream().map(action -> action.locator().name()).toList())
            || (marker.routePath() != null && !input.sourceEvidence().contains(marker.routePath()))) {
          return completed(RESOLUTION, ScreenshotResolution.approval(input.content(), marker.id(),
              "approval://screenshot-route/" + marker.id(), "ROUTE_EVIDENCE_INSUFFICIENT"));
        }
      }
      return CompletableFuture.completedFuture(Map.of(MARKERS, markers, RENDERED, input.content()));
    } catch (IllegalArgumentException invalid) {
      return completed(RESOLUTION, ScreenshotResolution.failed(input.content(), "invalid-manifest", "SCREENSHOT_MANIFEST_INVALID"));
    }
  }

  private CompletableFuture<Map<String, Object>> capture(AgentState state) {
    if (state.value(RESOLUTION).isPresent()) return CompletableFuture.completedFuture(Map.of());
    ResolutionInput input = input(state);
    @SuppressWarnings("unchecked") List<ScreenshotMarker> markers = (List<ScreenshotMarker>) state.value(MARKERS).orElse(List.of());
    String rendered = (String) state.value(RENDERED).orElse(input.content());
    Map<String, String> sessions = new HashMap<>();
    Set<String> capturedIds = new HashSet<>();
    try {
      for (ScreenshotMarker marker : markers) {
        String session = sessions.get(marker.loginProfileRef());
        if (session == null) {
          session = browser.open(input.taskId(), input.baseUrl(), marker.loginProfileRef());
          sessions.put(marker.loginProfileRef(), session);
          browser.login(session);
        }

        // Start each declared page path from the supplied app root, while preserving the profile's
        // authenticated context. Profiles never share a BrowserContext or session.
        browser.navigate(session, input.baseUrl());
        if (marker.routePath() != null && marker.menuPath().isEmpty()) {
          browser.navigate(session, marker.routePath());
        } else {
          for (String menuItem : marker.menuPath()) browser.clickMenuItem(session, menuItem);
        }
        for (ScreenshotAction action : marker.actions()) execute(session, action);

        BrowserSnapshot reached = browser.snapshot(session);
        if (!sameOrigin(input.baseUrl(), reached.url())) {
          return completed(RESOLUTION, ScreenshotResolution.failed(rendered, marker.id(), "TARGET_ORIGIN_INVALID"));
        }
        if (!containsTarget(reached.accessibilityText(), marker.target())) {
          return completed(RESOLUTION, ScreenshotResolution.failed(rendered, marker.id(), "TARGET_PAGE_NOT_REACHED"));
        }
        if (marker.routePath() != null && !marker.routePath().equals(pathOf(reached.url()))) {
          return completed(RESOLUTION, ScreenshotResolution.failed(rendered, marker.id(), "TARGET_ROUTE_NOT_REACHED"));
        }

        String artifact = browser.screenshot(session, marker.id(), marker.caption());
        if (!validScreenshotReference(artifact, input.taskId(), marker.id())) {
          return completed(RESOLUTION, ScreenshotResolution.failed(rendered, marker.id(), "SCREENSHOT_ARTIFACT_INVALID"));
        }
        if (occurrences(rendered, marker.raw()) != 1 || !capturedIds.add(marker.id())) {
          return completed(RESOLUTION, ScreenshotResolution.failed(rendered, marker.id(), "MARKER_REPLACEMENT_AMBIGUOUS"));
        }
        rendered = rendered.replace(marker.raw(), imageMarkup(input.content(), marker.caption(), artifact));
      }
      return CompletableFuture.completedFuture(Map.of(RENDERED, rendered, "capturedIds", capturedIds));
    } catch (RuntimeException failure) {
      String code = failure instanceof RestBrowserWorkerClient.BrowserOperationException browserFailure
          ? browserFailure.code() : "BROWSER_OPERATION_FAILED";
      String failedId = "screenshot-resolution";
      for (ScreenshotMarker marker : markers) {
        if (occurrences(rendered, marker.raw()) == 1) { failedId = marker.id(); break; }
      }
      return completed(RESOLUTION, ScreenshotResolution.failed(rendered, failedId, code));
    } finally {
      sessions.values().forEach(session -> {
        try { browser.close(session); } catch (RuntimeException ignored) { /* best-effort cleanup */ }
      });
    }
  }

  private CompletableFuture<Map<String, Object>> verify(AgentState state) {
    if (state.value(RESOLUTION).isPresent()) return CompletableFuture.completedFuture(Map.of());
    ResolutionInput input = input(state);
    String rendered = (String) state.value(RENDERED).orElse(input.content());
    if (rendered.contains("agent-studio:screenshot")) {
      return completed(RESOLUTION, ScreenshotResolution.failed(rendered, "leftover-marker", "SCREENSHOT_MARKER_UNRESOLVED"));
    }
    @SuppressWarnings("unchecked") Set<String> capturedIds = (Set<String>) state.value("capturedIds").orElse(Set.of());
    @SuppressWarnings("unchecked") List<ScreenshotMarker> markers = (List<ScreenshotMarker>) state.value(MARKERS).orElse(List.of());
    if (capturedIds.size() != markers.size()) {
      return completed(RESOLUTION, ScreenshotResolution.failed(rendered, "capture-count", "SCREENSHOT_COUNT_MISMATCH"));
    }
    return completed(RESOLUTION, ScreenshotResolution.complete(rendered));
  }

  private static ResolutionInput input(AgentState state) {
    return (ResolutionInput) state.value(INPUT).orElseThrow(() -> new IllegalStateException("graph input is missing"));
  }

  private static CompletableFuture<Map<String, Object>> completed(String key, Object value) {
    return CompletableFuture.completedFuture(Map.of(key, value));
  }

  private static boolean containsTarget(String accessibility, String target) {
    if (accessibility == null || accessibility.isBlank() || target == null || target.isBlank()) return false;
    String page = normalize(accessibility);
    String expected = normalize(target);
    return page.contains(expected);
  }

  private void execute(String session, ScreenshotAction action) {
    switch (action.type()) {
      case "click" -> browser.click(session, action.locator());
      case "fill" -> browser.fill(session, action.locator(), action.value());
      case "select" -> browser.select(session, action.locator(), action.value());
      case "check" -> browser.check(session, action.locator());
      case "uncheck" -> browser.uncheck(session, action.locator());
      case "wait" -> browser.waitFor(session, action.locator());
      default -> throw new IllegalArgumentException("unsupported screenshot action");
    }
  }

  private static String normalize(String value) {
    return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
  }

  private static boolean sameOrigin(String expectedBase, String reachedUrl) {
    try {
      URI expected = URI.create(expectedBase).normalize();
      URI reached = URI.create(reachedUrl).normalize();
      return expected.getScheme().equalsIgnoreCase(reached.getScheme())
          && expected.getHost().equalsIgnoreCase(reached.getHost())
          && effectivePort(expected) == effectivePort(reached);
    } catch (RuntimeException invalid) { return false; }
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String pathOf(String url) {
    try { return URI.create(url).normalize().getPath(); }
    catch (RuntimeException invalid) { return ""; }
  }

  private static boolean validScreenshotReference(String value, UUID taskId, String markerId) {
    if (value == null || value.isBlank() || value.length() > 2048 || value.contains("\\")) return false;
    try {
      URI uri = URI.create(value).normalize();
      if (!"artifact".equalsIgnoreCase(uri.getScheme())) return false;
      if (!taskId.toString().equalsIgnoreCase(uri.getHost())) return false;
      String path = uri.getPath();
      if (path == null || path.isBlank()) return false;
      for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return false;
      String expectedPath = "/" + markerId + "/" + markerId + ".png";
      return expectedPath.equals(path) && uri.getUserInfo() == null
          && uri.getQuery() == null && uri.getFragment() == null;
    } catch (RuntimeException invalid) { return false; }
  }

  private static String imageMarkup(String original, String caption, String artifact) {
    if (original.toLowerCase(java.util.Locale.ROOT).contains("<html")) {
      return "<img src=\"" + artifact + "\" alt=\"" + caption.replace("&", "&amp;").replace("\"", "&quot;") + "\">";
    }
    String safeCaption = caption.replace("\\", "\\\\").replace("]", "\\]").replace("(", "\\(").replace(")", "\\)");
    return "![" + safeCaption + "](<" + artifact + ">)";
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int start = 0; (start = text.indexOf(needle, start)) >= 0; start += needle.length()) count++;
    return count;
  }

  private record ResolutionInput(UUID taskId, String baseUrl, String content,
                                 List<String> sourceEvidence, Set<String> allowedLoginProfileRefs) implements java.io.Serializable { }
}

record ScreenshotResolution(String content, boolean completed, String failedMarkerId,
                            String approvalReference, String errorCode) implements java.io.Serializable {
  static ScreenshotResolution complete(String content) { return new ScreenshotResolution(content, true, null, null, null); }
  static ScreenshotResolution failed(String content, String id, String errorCode) { return new ScreenshotResolution(content, false, id, null, errorCode); }
  static ScreenshotResolution approval(String content, String id, String ref, String errorCode) { return new ScreenshotResolution(content, false, id, ref, errorCode); }
  ScreenshotApprovalRequest approvalRequest() {
    return approvalReference == null ? null
        : new ScreenshotApprovalRequest("SCREENSHOT_ROUTE_AMBIGUITY", failedMarkerId, errorCode, approvalReference);
  }
}

/** Typed payload for the workflow adapter to convert into a Temporal approval request. */
record ScreenshotApprovalRequest(String type, String markerId, String reasonCode,
                                 String reference) implements java.io.Serializable { }
