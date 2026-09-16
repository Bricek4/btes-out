package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ArtifactRendererTest {
  @Test void documentUpdateKeepsManualSectionsAndAddsMarker() {
    var result = new DocumentRenderer().render(new DocumentTemplate("docs/guide.md",
        "# {{title}}\n<!-- agent-studio:manual:start -->default<!-- agent-studio:manual:end -->\n{{screenshots}}"),
        new DocumentRequest("Guide", "written by user", "", "<!-- agent-studio:screenshot:v1 {\"id\":\"home\",\"loginProfileRef\":\"member\",\"target\":\"home\",\"caption\":\"Home\"} -->"));
    assertEquals("docs/guide.md", result.path());
    assertTrue(result.content().contains("written by user"));
    assertEquals(1, ScreenshotMarkers.parseAll(result.content()).size());
  }

  @Test void htmlRemovesExecutableTemplateContentAndRejectsUnsafeLinks() {
    var html = new HtmlRenderer().render("<nav>{{title}}</nav><script>alert(1)</script><a href=\"javascript:bad\">x</a>", "Example");
    assertFalse(html.contains("script"));
    assertFalse(html.contains("javascript:"));
    assertTrue(html.contains("viewport"));
  }

  @Test void htmlSanitizerRemovesMalformedAndEntityObfuscatedExecutableMarkup() {
    var html = new HtmlRenderer().render(
        "<main><img src=x onerror=alert(1)><a href=java&#x73;cript:alert(2)>open</a>" +
            "<svg><script>alert(3)</script></svg></main>", "Safe page");

    assertFalse(html.toLowerCase().contains("onerror"));
    assertFalse(html.toLowerCase().contains("javascript:"));
    assertFalse(html.toLowerCase().contains("<svg"));
    assertTrue(html.contains("open"));
  }
}
