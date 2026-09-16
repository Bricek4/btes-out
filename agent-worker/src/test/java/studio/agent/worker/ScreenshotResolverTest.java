package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScreenshotResolverTest {
  private static final String MARKER = "<!-- agent-studio:screenshot:v1 {\"id\":\"users\",\"loginProfileRef\":\"admin\",\"target\":\"user list\",\"menuPath\":[\"Administration\",\"Users\"],\"caption\":\"Users\"} -->";
  private static final String ACTION_MARKER = "<!-- agent-studio:screenshot:v1 {\"id\":\"filtered-users\",\"loginProfileRef\":\"admin\",\"target\":\"filtered user list\",\"menuPath\":[\"Administration\",\"Users\"],\"actions\":["
      + "{\"type\":\"fill\",\"locator\":{\"kind\":\"label\",\"name\":\"Search\"},\"value\":\"Ada\"},"
      + "{\"type\":\"select\",\"locator\":{\"kind\":\"label\",\"name\":\"Status\"},\"value\":\"Active\"},"
      + "{\"type\":\"check\",\"locator\":{\"kind\":\"role\",\"role\":\"checkbox\",\"name\":\"Include invited\"}},"
      + "{\"type\":\"uncheck\",\"locator\":{\"kind\":\"test-id\",\"name\":\"include-disabled\"}},"
      + "{\"type\":\"click\",\"locator\":{\"kind\":\"role\",\"role\":\"button\",\"name\":\"Apply\"}},"
      + "{\"type\":\"wait\",\"locator\":{\"kind\":\"test-id\",\"name\":\"results-ready\"}}],\"caption\":\"Filtered users\"} -->";

  @Test void followsOnlyDeclaredSemanticPathAndReplacesEveryMarker() {
    var browser = new RecordingBrowser();
    UUID taskId = UUID.randomUUID();
    var result = new ScreenshotResolver(browser).resolve(taskId, "https://fixture.test", MARKER, List.of("Administration", "Users"), Set.of("admin"));
    assertTrue(result.completed());
    assertTrue(result.content().contains("artifact://" + taskId + "/users/users.png"));
    assertFalse(result.content().contains("agent-studio:screenshot"));
    assertEquals(List.of("Administration", "Users"), browser.clicked);
  }

  @Test void verifiesTargetAfterNestedMenuNavigationInsteadOfOnInitialPage() {
    var browser = new RecordingBrowser();
    browser.targetAppearsAfterClicks = 2;
    browser.initialSnapshot = "button Administration";
    browser.reachedSnapshot = "heading User list";

    var result = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test", MARKER,
        List.of("Administration", "Users"), Set.of("admin"));

    assertTrue(result.completed());
    assertEquals(2, browser.clicksBeforeFinalSnapshot);
    assertFalse(result.content().contains("agent-studio:screenshot"));
  }

  @Test void failsWithTheMarkerIdWhenDeclaredPathDoesNotReachTarget() {
    var browser = new RecordingBrowser();
    browser.reachedSnapshot = "heading Dashboard";

    var result = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test", MARKER,
        List.of("Administration", "Users"), Set.of("admin"));

    assertFalse(result.completed());
    assertEquals("users", result.failedMarkerId());
    assertEquals("TARGET_PAGE_NOT_REACHED", result.errorCode());
  }

  @Test void refusesScreenshotResultsThatAreNotPngArtifactReferences() {
    var browser = new RecordingBrowser();
    browser.screenshotReference = "https://untrusted.example/pixel.svg";

    var result = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test", MARKER,
        List.of("Administration", "Users"), Set.of("admin"));

    assertFalse(result.completed());
    assertEquals("users", result.failedMarkerId());
    assertEquals("SCREENSHOT_ARTIFACT_INVALID", result.errorCode());

    browser.screenshotReference = "artifact://docs/fake.png";
    var wrongKind = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test", MARKER,
        List.of("Administration", "Users"), Set.of("admin"));
    assertFalse(wrongKind.completed());
    assertEquals("SCREENSHOT_ARTIFACT_INVALID", wrongKind.errorCode());
  }

  @Test void returnsApprovalInsteadOfCrawlingWhenEvidenceDoesNotContainDeclaredPath() {
    var result = new ScreenshotResolver(new RecordingBrowser()).resolve(UUID.randomUUID(), "https://fixture.test", MARKER, List.of("Administration"), Set.of("admin"));
    assertFalse(result.completed());
    assertEquals("users", result.failedMarkerId());
    assertNotNull(result.approvalReference());
    assertEquals("SCREENSHOT_ROUTE_AMBIGUITY", result.approvalRequest().type());
    assertEquals("ROUTE_EVIDENCE_INSUFFICIENT", result.approvalRequest().reasonCode());
  }

  @Test void executesEveryTypedSemanticActionWithoutSelectorsOrScripts() {
    var browser = new RecordingBrowser();
    browser.reachedSnapshot = "heading filtered user list";
    var evidence = List.of("Administration", "Users", "Search", "Status", "Include invited",
        "include-disabled", "Apply", "results-ready");

    var result = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test",
        ACTION_MARKER, evidence, Set.of("admin"));

    assertTrue(result.completed());
    assertEquals(List.of(
        "menuitem:Administration", "menuitem:Users", "fill:label:Search=Ada",
        "select:label:Status=Active", "check:role:Include invited", "uncheck:test-id:include-disabled",
        "click:role:Apply", "wait:test-id:results-ready"), browser.operations);
  }

  private static final class RecordingBrowser implements BrowserWorkerClient {
    private final java.util.ArrayList<String> clicked = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> operations = new java.util.ArrayList<>();
    private int targetAppearsAfterClicks = 0;
    private int clicksBeforeFinalSnapshot;
    private String initialSnapshot = "Administration Users user list";
    private String reachedSnapshot = "Administration Users user list";
    private String screenshotReference;
    private UUID taskId;
    public String open(UUID taskId, String baseUrl, String profile) { this.taskId = taskId; return "session"; }
    public void login(String sessionId) { }
    public BrowserSnapshot snapshot(String sessionId) {
      if (clicked.size() >= targetAppearsAfterClicks) {
        clicksBeforeFinalSnapshot = clicked.size();
        return new BrowserSnapshot("https://fixture.test/users", reachedSnapshot);
      }
      return new BrowserSnapshot("https://fixture.test", initialSnapshot);
    }
    public void navigate(String sessionId, String target) { }
    public void click(String sessionId, SemanticLocator locator) { operations.add("click:" + locator.kind() + ":" + locator.name()); }
    public void clickMenuItem(String sessionId, String name) { clicked.add(name); operations.add("menuitem:" + name); }
    public void fill(String sessionId, SemanticLocator locator, String value) { operations.add("fill:" + locator.kind() + ":" + locator.name() + "=" + value); }
    public void select(String sessionId, SemanticLocator locator, String value) { operations.add("select:" + locator.kind() + ":" + locator.name() + "=" + value); }
    public void check(String sessionId, SemanticLocator locator) { operations.add("check:" + locator.kind() + ":" + locator.name()); }
    public void uncheck(String sessionId, SemanticLocator locator) { operations.add("uncheck:" + locator.kind() + ":" + locator.name()); }
    public void waitFor(String sessionId, SemanticLocator locator) { operations.add("wait:" + locator.kind() + ":" + locator.name()); }
    public String screenshot(String sessionId, String markerId, String caption) { return screenshotReference == null ? "artifact://" + taskId + "/" + markerId + "/" + markerId + ".png" : screenshotReference; }
    public void close(String sessionId) { }
  }
}
