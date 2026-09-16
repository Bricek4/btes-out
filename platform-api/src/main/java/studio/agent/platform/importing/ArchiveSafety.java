package studio.agent.platform.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

public final class ArchiveSafety {
  private ArchiveSafety() {}
  public record Inspection(int entries, long expandedBytes) {}

  public static Inspection inspect(byte[] archive, int maxEntries, long maxExpandedBytes) {
    if (archive == null || archive.length == 0) throw new IllegalArgumentException("ZIP archive is required");
    int entries = 0; long expanded = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        var name = entry.getName();
        var normalized = Path.of(name).normalize();
        if (name.startsWith("/") || name.startsWith("\\") || normalized.isAbsolute() || normalized.startsWith("..") || name.indexOf('\0') >= 0) {
          throw new IllegalArgumentException("ZIP contains an unsafe path");
        }
        if (++entries > maxEntries) throw new IllegalArgumentException("ZIP contains too many entries");
        var buffer = new byte[8192];
        for (int read; (read = zip.read(buffer)) >= 0;) {
          expanded += read;
          if (expanded > maxExpandedBytes) throw new IllegalArgumentException("ZIP expanded size exceeds the limit");
        }
      }
      return new Inspection(entries, expanded);
    } catch (IOException exception) { throw new IllegalArgumentException("ZIP archive is invalid", exception); }
  }
}
