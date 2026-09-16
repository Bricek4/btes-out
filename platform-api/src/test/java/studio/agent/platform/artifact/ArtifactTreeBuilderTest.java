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
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> ArtifactTreeBuilder.build(List.of(entry(id, "report\n.html", "HTML"))));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> ArtifactTreeBuilder.build(List.of(entry(id, "a.md", "DOC"), entry(UUID.randomUUID(), "a.md", "DOC"))));
  }

  private static ArtifactTreeBuilder.Entry entry(UUID id, String name, String kind) {
    return new ArtifactTreeBuilder.Entry(id, name, kind, 1, "text/plain", 12, "a".repeat(64));
  }
}
