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
}
