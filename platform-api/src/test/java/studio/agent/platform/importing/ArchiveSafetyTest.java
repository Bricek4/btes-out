package studio.agent.platform.importing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ArchiveSafetyTest {
  @Test
  void accepts_a_small_relative_archive_and_rejects_traversal() throws Exception {
    assertEquals(2, ArchiveSafety.inspect(zip("README.md", "ok", "src/App.java", "class App {}"), 10, 1024).entries());
    assertThrows(IllegalArgumentException.class, () -> ArchiveSafety.inspect(zip("../escape", "bad"), 10, 1024));
    assertThrows(IllegalArgumentException.class, () -> ArchiveSafety.inspect(zip("/absolute", "bad"), 10, 1024));
  }

  @Test
  void rejects_archive_entry_and_expanded_size_limits() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ArchiveSafety.inspect(zip("a", "1", "b", "2"), 1, 1024));
    assertThrows(IllegalArgumentException.class, () -> ArchiveSafety.inspect(zip("large", "x".repeat(64)), 10, 16));
  }

  private byte[] zip(String... parts) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      for (int i = 0; i < parts.length; i += 2) {
        zip.putNextEntry(new ZipEntry(parts[i]));
        zip.write(parts[i + 1].getBytes());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
