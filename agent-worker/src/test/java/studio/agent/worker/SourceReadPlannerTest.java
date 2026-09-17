package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SourceReadPlannerTest {
  @Test
  void plans_every_project_text_file_without_truncating_chunks() throws Exception {
    String source = "class Main {\n" + "  // evidence\n".repeat(2_500) + "}\n";
    byte[] archive = zip(Map.of(
        "src/Main.java", source,
        "docs/guide.md", "# Guide\nUse the service.\n",
        "node_modules/pkg/index.js", "module.exports = {};\n",
        "target/app.properties", "build=true\n",
        "assets/logo.png", new String(new char[] {'\u0000', '\u0001'}).getBytes(StandardCharsets.ISO_8859_1)));

    SourceReadPlan plan = SourceReadPlanner.plan(archive);

    assertEquals(5, plan.totalEntries());
    assertEquals(Arrays.asList("assets/logo.png", "docs/guide.md", "node_modules/pkg/index.js",
        "src/Main.java", "target/app.properties"), plan.files().stream().map(SourceReadFile::path).toList());
    assertEquals(SourceFileCategory.BINARY, plan.file("assets/logo.png").category());
    assertEquals(SourceFileCategory.DOCUMENT, plan.file("docs/guide.md").category());
    assertEquals(SourceFileCategory.DEPENDENCY, plan.file("node_modules/pkg/index.js").category());
    assertEquals(SourceFileCategory.GENERATED, plan.file("target/app.properties").category());
    assertEquals(SourceFileCategory.SOURCE, plan.file("src/Main.java").category());
    assertTrue(plan.file("node_modules/pkg/index.js").chunks().isEmpty());
    assertTrue(plan.file("target/app.properties").chunks().isEmpty());
    assertTrue(plan.file("src/Main.java").chunks().size() > 1);
    assertEquals(source, plan.file("src/Main.java").chunks().stream()
        .map(SourceReadChunk::content).reduce("", String::concat));
    assertTrue(plan.files().stream().allMatch(file -> file.category() == SourceFileCategory.BINARY
        ? file.status() == SourceReadStatus.SKIPPED
        : file.category() == SourceFileCategory.SOURCE || file.category() == SourceFileCategory.DOCUMENT
            ? file.status() == SourceReadStatus.PLANNED : file.status() == SourceReadStatus.SKIPPED));
    assertFalse(plan.archiveSha256().isBlank());
    assertEquals(64, plan.file("src/Main.java").sha256().length());
    assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.getBytes(StandardCharsets.UTF_8))), plan.file("src/Main.java").sha256());
  }

  @Test
  void rejects_unsafe_archive_paths_before_creating_a_read_plan() throws Exception {
    assertThrows(SourceReadException.class, () -> SourceReadPlanner.plan(zip(Map.of("../escape.java", "bad"))));
  }

  @Test
  void plans_a_plain_text_source_for_legacy_gateway_implementations() {
    SourceReadPlan plan = SourceReadPlanner.planText("source.txt", "route /health\n".getBytes(StandardCharsets.UTF_8));

    assertEquals(1, plan.totalEntries());
    assertEquals(SourceFileCategory.SOURCE, plan.file("source.txt").category());
    assertEquals("route /health\n", plan.file("source.txt").chunks().getFirst().content());
  }

  private static byte[] zip(Map<String, ?> entries) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        if (entry.getValue() instanceof String text) zip.write(text.getBytes(StandardCharsets.UTF_8));
        else if (entry.getValue() instanceof byte[] bytes) zip.write(bytes);
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
