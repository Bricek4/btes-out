package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
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
    assertThrows(IllegalArgumentException.class,
        () -> ArtifactZip.create(java.util.Map.of("bad\rname", new byte[] {1}), 100));
    assertThrows(IllegalArgumentException.class,
        () -> ArtifactZip.create(java.util.Map.of("large.bin", new byte[101]), 100));
  }
}
