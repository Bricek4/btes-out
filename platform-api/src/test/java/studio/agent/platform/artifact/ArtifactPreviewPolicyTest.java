package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ArtifactPreviewPolicyTest {
  @Test
  void inlinesOnlyInertMediaTypes() {
    assertEquals(MediaType.IMAGE_PNG, ArtifactController.safePreviewMediaType("image/png"));
    assertEquals(MediaType.APPLICATION_JSON, ArtifactController.safePreviewMediaType("application/json"));
    assertEquals(MediaType.TEXT_PLAIN, ArtifactController.safePreviewMediaType("text/html"));
    assertEquals(MediaType.TEXT_PLAIN, ArtifactController.safePreviewMediaType("image/svg+xml"));
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, ArtifactController.safePreviewMediaType("application/pdf"));
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, ArtifactController.safePreviewMediaType("invalid media"));
  }
}
