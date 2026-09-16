package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ArtifactVerifierTest {
  @Test
  void acceptsResolvedHtmlAndReportsOnlyChecks() throws Exception {
    byte[] content = "<!doctype html><html><body>Ready</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var report = ArtifactVerifier.verify("text/html", content, content.length, sha(content));

    assertTrue(report.valid());
    assertEquals("OK", report.code());
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("placeholders") && c.passed()));
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("activeContent") && c.passed()));
    assertFalse(report.toJson().contains("Ready"));
  }

  @Test
  void rejectsChecksumMismatchAndUnresolvedPlaceholders() throws Exception {
    byte[] content = "<html>{{ screenshot:admin-home }}</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var report = ArtifactVerifier.verify("text/html", content, content.length, "0".repeat(64));

    assertFalse(report.valid());
    assertEquals("VERIFICATION_FAILED", report.code());
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("checksum") && !c.passed()));
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("placeholders") && !c.passed()));
  }

  @Test
  void validatesDeclaredImageTypeFromBytes() throws Exception {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    assertTrue(ArtifactVerifier.verify("image/png", png, png.length, sha(png)).valid());
    assertFalse(ArtifactVerifier.verify("image/jpeg", png, png.length, sha(png)).valid());
  }

  @Test
  void rejectsActiveHtmlContent() throws Exception {
    byte[] content = "<!doctype html><html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var report = ArtifactVerifier.verify("text/html", content, content.length, sha(content));

    assertFalse(report.valid());
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("activeContent") && !c.passed()));
  }

  @Test
  void rejectsMalformedJsonArtifacts() throws Exception {
    byte[] content = "{not-json}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var report = ArtifactVerifier.verify("application/json", content, content.length, sha(content));

    assertFalse(report.valid());
    assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("json") && !c.passed()));
  }

  private static String sha(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}
