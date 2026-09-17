package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import studio.agent.platform.security.CurrentUser;

class ArtifactControllerExportTest {
  @Test
  void returns_a_materialized_zip_after_authorization_finishes() {
    var user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "member@example.test", "MEMBER");
    var taskId = UUID.randomUUID();
    var expected = new byte[] {1, 2, 3};
    var plan = new ArtifactService.ExportPlan(List.of(), 0);
    var service = new StubArtifactService(plan, expected);

    var response = new ArtifactController(service).export(user, taskId);

    assertArrayEquals(expected, response.getBody());
    assertEquals("application/zip", response.getHeaders().getContentType().toString());
  }

  @Test
  void returns_a_materialized_artifact_download_after_authorization_finishes() {
    var service = new StubArtifactService(new ArtifactService.ExportPlan(List.of(), 0), new byte[] {4, 5, 6});
    var user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "member@example.test", "MEMBER");
    var artifactId = UUID.randomUUID();
    var stored = new ArtifactService.StoredVersion("README.md", 1, "artifact-key", "text/markdown", 3, "a".repeat(64));
    service.download = stored;

    var response = new ArtifactController(service).download(user, artifactId, 1);

    assertArrayEquals(new byte[] {4, 5, 6}, response.getBody());
    assertEquals("text/markdown", response.getHeaders().getContentType().toString());
  }

  private static final class StubArtifactService extends ArtifactService {
    private final ExportPlan plan;
    private final byte[] bytes;
    private StoredVersion download;

    private StubArtifactService(ExportPlan plan, byte[] bytes) {
      super(null, null);
      this.plan = plan;
      this.bytes = bytes;
    }

    @Override
    ExportPlan exportPlan(CurrentUser user, UUID taskId) {
      return plan;
    }

    @Override
    byte[] exportBytes(ExportPlan value) {
      return bytes;
    }

    @Override
    StoredVersion readableVersion(CurrentUser user, UUID artifactId, Integer version) {
      return download;
    }

    @Override
    byte[] downloadBytes(StoredVersion value) {
      return bytes;
    }
  }
}
