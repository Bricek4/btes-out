package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ArtifactZipTest {
  @Test
  void exportsStableSafePaths() throws Exception {
    var files = new LinkedHashMap<String, byte[]>();
    files.put("docs/guide.md", "guide".getBytes());
    files.put("images/home.png", new byte[] {1, 2, 3});
    byte[] zip = ArtifactZip.create(files, 1024);

    try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      assertEquals("docs/guide.md", in.getNextEntry().getName());
      assertEquals("guide", new String(in.readAllBytes()));
      assertEquals("images/home.png", in.getNextEntry().getName());
    }
  }

  @Test
  void rejectsTraversalAndTotalLimit() {
    assertThrows(IllegalArgumentException.class,
        () -> ArtifactZip.create(java.util.Map.of("../secret", new byte[] {1}), 100));
    assertEquals("bad_name", ArtifactZip.safeName("bad\rname"));
    assertEquals("docs/guide.md", ArtifactZip.safeName("./docs//guide.md"));
    assertThrows(IllegalArgumentException.class,
        () -> ArtifactZip.create(java.util.Map.of("large.bin", new byte[101]), 100));
  }

  @Test
  void deterministicallyEscapesReservedFallbackNames() {
    var id = UUID.fromString("deadbeef-0000-0000-0000-000000000000");
    var used = new HashSet<String>();
    assertEquals("a~deadbeef000000000000000000000000-1",
        ArtifactZip.uniqueName("a", id, Set.of("a", "a~deadbeef000000000000000000000000"), used));
  }
}
