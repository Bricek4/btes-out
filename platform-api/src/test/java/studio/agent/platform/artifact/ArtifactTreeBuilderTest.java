package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactTreeBuilderTest {
  @Test
  void buildsStableFolderTreeWithoutLeakingSiblingMetadata() {
    var guideId = UUID.randomUUID();
    var imageId = UUID.randomUUID();
    var readmeId = UUID.randomUUID();

    var tree = ArtifactTreeBuilder.build(List.of(
        entry(imageId, "docs/images/home.png", "SCREENSHOT"),
        entry(readmeId, "README.md", "DOC"),
        entry(guideId, "docs/guide.md", "DOC")));

    assertEquals(List.of("docs", "README.md"), tree.stream().map(ArtifactTreeBuilder.Node::name).toList());
    assertEquals(List.of("images", "guide.md"), tree.getFirst().children().stream().map(ArtifactTreeBuilder.Node::name).toList());
    assertEquals(imageId, tree.getFirst().children().getFirst().children().getFirst().artifactId());
    assertEquals("docs/images/home.png", tree.getFirst().children().getFirst().children().getFirst().path());
  }

  @Test
  void rejectsTraversalAndDuplicatePaths() {
    var id = UUID.randomUUID();
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> ArtifactTreeBuilder.build(List.of(entry(id, "../secret", "DOC"))));
    var legacy = ArtifactTreeBuilder.build(List.of(entry(id, "./docs//report\n.html", "HTML")));
    assertEquals("docs", legacy.getFirst().name());
    assertEquals("report_.html", legacy.getFirst().children().getFirst().name());
    var duplicate = ArtifactTreeBuilder.build(List.of(entry(id, "a//file.md", "DOC"),
        entry(UUID.randomUUID(), "a/file.md", "DOC"), entry(UUID.randomUUID(), "a", "DOC")));
    assertEquals(2, duplicate.size());
    assertEquals("a", duplicate.getFirst().name());
    assertEquals(2, duplicate.getFirst().children().size());
  }

  @Test
  void keepsTreeReadableWhenCanonicalFallbackIsAlsoAFolder() {
    var id = UUID.fromString("deadbeef-0000-0000-0000-000000000000");
    var tree = ArtifactTreeBuilder.build(List.of(entry(id, "a", "DOC"),
        entry(UUID.randomUUID(), "a/file", "DOC"),
        entry(UUID.randomUUID(), "a~deadbeef000000000000000000000000/file", "DOC")));

    assertEquals(3, tree.size());
    assertEquals("a~deadbeef000000000000000000000000-1", tree.get(2).name());
  }

  private static ArtifactTreeBuilder.Entry entry(UUID id, String name, String kind) {
    return new ArtifactTreeBuilder.Entry(id, name, kind, 1, "text/plain", 12, "a".repeat(64));
  }
}
