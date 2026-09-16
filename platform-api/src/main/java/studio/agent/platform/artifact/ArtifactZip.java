package studio.agent.platform.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ArtifactZip {
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

  static String safeName(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("invalid artifact path");
    }
    for (var segment : name.split("/", -1)) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
          || segment.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("invalid artifact path");
      }
    }
    return name;
  }
}
