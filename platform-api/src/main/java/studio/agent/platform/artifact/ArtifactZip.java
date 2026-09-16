package studio.agent.platform.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArtifactZip {
  private ArtifactZip() { }

  static byte[] create(Map<String, byte[]> files, long maxUncompressedBytes) {
    if (maxUncompressedBytes < 0) throw new IllegalArgumentException("ZIP limit must be non-negative");
    long total = 0;
    var names = new HashSet<String>();
    try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
      for (var file : files.entrySet()) {
        String name = safeName(file.getKey());
        if (!names.add(name)) throw new IllegalArgumentException("duplicate ZIP entry");
        total = Math.addExact(total, file.getValue().length);
        if (total > maxUncompressedBytes) throw new IllegalArgumentException("artifact export exceeds size limit");
        zip.putNextEntry(new ZipEntry(name));
        zip.write(file.getValue());
        zip.closeEntry();
      }
      zip.finish();
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("artifact ZIP export failed", exception);
    }
  }

  public static String safeName(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("invalid artifact path");
    }
    var safe = new java.util.ArrayList<String>();
    for (var segment : name.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment)) continue;
      if ("..".equals(segment)) throw new IllegalArgumentException("invalid artifact path");
      var normalized = new StringBuilder(segment.length());
      segment.codePoints().forEach(codePoint -> normalized.appendCodePoint(Character.isISOControl(codePoint) ? '_' : codePoint));
      if (!normalized.toString().isBlank()) safe.add(normalized.toString());
    }
    if (safe.isEmpty()) throw new IllegalArgumentException("invalid artifact path");
    return String.join("/", safe);
  }

  static String uniqueName(String normalized, UUID artifactId, Set<String> reserved, Set<String> used) {
    String candidate = normalized;
    if (reserved.contains(candidate) || used.contains(candidate)) {
      int slash = candidate.lastIndexOf('/');
      String parent = slash < 0 ? "" : candidate.substring(0, slash + 1);
      String filename = slash < 0 ? candidate : candidate.substring(slash + 1);
      candidate = parent + filename + "~" + artifactId.toString().substring(0, 8);
    }
    if (reserved.contains(candidate) || !used.add(candidate)) {
      throw new IllegalArgumentException("artifact paths cannot be disambiguated");
    }
    return candidate;
  }
}
