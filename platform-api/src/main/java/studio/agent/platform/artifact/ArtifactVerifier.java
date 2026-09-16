package studio.agent.platform.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

final class ArtifactVerifier {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^{}]+}}|\\[\\[[^\\[\\]]+]]");
  private static final Pattern ACTIVE_HTML = Pattern.compile(
      "(?is)<\\s*(script|iframe|object|embed|base)\\b|\\bon[a-z]+\\s*=|\\b(?:href|src)\\s*=\\s*['\"]?\\s*javascript:");
  private static final ObjectMapper JSON = new ObjectMapper();

  private ArtifactVerifier() { }

  static Report verify(String mediaType, byte[] content, long expectedSize, String expectedSha256) {
    var checks = new ArrayList<Check>();
    checks.add(new Check("size", content.length == expectedSize));
    checks.add(new Check("checksum", sha256(content).equalsIgnoreCase(expectedSha256)));
    String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    if (normalized.startsWith("image/")) {
      checks.add(new Check("mediaType", matchesImage(normalized, content)));
    } else if (isText(normalized)) {
      var text = new String(content, StandardCharsets.UTF_8);
      checks.add(new Check("placeholders", !PLACEHOLDER.matcher(text).find()));
      if (normalized.equals("text/html") || normalized.equals("application/xhtml+xml")) {
        checks.add(new Check("activeContent", !ACTIVE_HTML.matcher(text).find()));
      }
      if (normalized.equals("application/json")) checks.add(new Check("json", validJson(content)));
    } else {
      checks.add(new Check("content", content.length > 0));
    }
    boolean valid = checks.stream().allMatch(Check::passed);
    return new Report(valid, valid ? "OK" : "VERIFICATION_FAILED", List.copyOf(checks));
  }

  private static boolean isText(String mediaType) {
    return mediaType.startsWith("text/") || mediaType.equals("application/json")
        || mediaType.equals("application/xhtml+xml") || mediaType.equals("application/xml");
  }

  private static boolean matchesImage(String mediaType, byte[] bytes) {
    return switch (mediaType) {
      case "image/png" -> startsWith(bytes, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
      case "image/jpeg" -> startsWith(bytes, new int[] {0xff, 0xd8, 0xff});
      case "image/gif" -> startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
          || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII));
      case "image/webp" -> bytes.length >= 12 && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
          && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
      default -> false;
    };
  }

  private static boolean startsWith(byte[] bytes, int[] prefix) {
    if (bytes.length < prefix.length) return false;
    for (int index = 0; index < prefix.length; index++) if ((bytes[index] & 0xff) != prefix[index]) return false;
    return true;
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) return false;
    for (int index = 0; index < prefix.length; index++) if (bytes[index] != prefix[index]) return false;
    return true;
  }

  private static boolean validJson(byte[] bytes) {
    try {
      return JSON.readTree(bytes) != null;
    } catch (Exception exception) {
      return false;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  record Check(String name, boolean passed) { }

  record Report(boolean valid, String code, List<Check> checks) {
    String toJson() {
      return JSON.writeValueAsString(this);
    }
  }
}
