package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenshotMarkerTest {
  private static final String MARKER = "<!-- agent-studio:screenshot:v1 {\"id\":\"users-admin\",\"loginProfileRef\":\"admin-profile\",\"target\":\"user list\",\"menuPath\":[\"Administration\",\"Users\"],\"caption\":\"Admin user list\"} -->";

  @Test void parsesOnlyTheVersionedExactMarkerShape() {
    var marker = ScreenshotMarkers.parseAll("# Guide\n" + MARKER).getFirst();
    assertEquals("users-admin", marker.id());
    assertEquals(List.of("Administration", "Users"), marker.menuPath());
  }

  @Test void rejectsDuplicateIdsAndUnknownFields() {
    assertThrows(IllegalArgumentException.class, () -> ScreenshotMarkers.parseAll(MARKER + "\n" + MARKER));
    assertThrows(IllegalArgumentException.class, () -> ScreenshotMarkers.parseAll(
        "<!-- agent-studio:screenshot:v1 {\"id\":\"x\",\"loginProfileRef\":\"p\",\"target\":\"t\",\"caption\":\"c\",\"url\":\"/anything\"} -->"));
  }

  @Test void parsesBoundedTypedSemanticActionsAndRejectsSelectorEscapes() {
    String marker = "<!-- agent-studio:screenshot:v1 {\"id\":\"create-user\",\"loginProfileRef\":\"admin-profile\",\"target\":\"new user\",\"routePath\":\"/users/new\",\"menuPath\":[\"Administration\",\"Users\"],\"actions\":["
        + "{\"type\":\"click\",\"locator\":{\"kind\":\"role\",\"role\":\"button\",\"name\":\"Add user\"}},"
        + "{\"type\":\"fill\",\"locator\":{\"kind\":\"label\",\"name\":\"Display name\"},\"value\":\"Example user\"},"
        + "{\"type\":\"wait\",\"locator\":{\"kind\":\"test-id\",\"name\":\"user-editor\"}}],\"caption\":\"New user form\"} -->";

    var parsed = ScreenshotMarkers.parseAll(marker).getFirst();
    assertEquals(3, parsed.actions().size());
    assertEquals(new SemanticLocator("label", null, "Display name"), parsed.actions().get(1).locator());
    assertEquals("Example user", parsed.actions().get(1).value());
    assertThrows(IllegalArgumentException.class, () -> ScreenshotMarkers.parseAll(marker.replace(
        "{\"kind\":\"label\",\"name\":\"Display name\"}",
        "{\"kind\":\"css\",\"name\":\"#display-name\"}")));
  }
}
