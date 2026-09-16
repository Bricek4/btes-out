package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScreenshotResolverTest {
  private static final String MARKER = "<!-- agent-studio:screenshot:v1 {\"id\":\"users\",\"loginProfileRef\":\"admin\",\"target\":\"user list\",\"menuPath\":[\"Administration\",\"Users\"],\"caption\":\"Users\"} -->";

  @Test void followsOnlyDeclaredSemanticPathAndReplacesEveryMarker() {
    var browser = new RecordingBrowser();
    var result = new ScreenshotResolver(browser).resolve(UUID.randomUUID(), "https://fixture.test", MARKER, List.of("Administration", "Users"));
    assertTrue(result.completed());
    assertTrue(result.content().contains("artifact://screenshots/users.png"));
    assertFalse(result.content().contains("agent-studio:screenshot"));
    assertEquals(List.of("Administration", "Users"), browser.clicked);
  }

  @Test void returnsApprovalInsteadOfCrawlingWhenEvidenceDoesNotContainDeclaredPath() {
    var result = new ScreenshotResolver(new RecordingBrowser()).resolve(UUID.randomUUID(), "https://fixture.test", MARKER, List.of("Administration"));
    assertFalse(result.completed());
    assertEquals("users", result.failedMarkerId());
    assertNotNull(result.approvalReference());
  }

  private static final class RecordingBrowser implements BrowserWorkerClient {
    private final java.util.ArrayList<String> clicked = new java.util.ArrayList<>();
    public String open(UUID taskId, String baseUrl, String profile) { return "session"; }
    public void login(String sessionId) { }
    public BrowserSnapshot snapshot(String sessionId) { return new BrowserSnapshot("https://fixture.test", "Administration Users user list"); }
    public void navigate(String sessionId, String target) { }
    public void click(String sessionId, String name) { clicked.add(name); }
    public String screenshot(String sessionId, String markerId) { return "artifact://screenshots/" + markerId + ".png"; }
    public void close(String sessionId) { }
  }
}
