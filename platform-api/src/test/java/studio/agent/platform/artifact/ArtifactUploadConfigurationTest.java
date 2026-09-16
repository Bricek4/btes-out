package studio.agent.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArtifactUploadConfigurationTest {
  @Test
  void permitsConfiguredReplacementLimitWithBoundedMultipartOverhead() {
    var element = new ArtifactUploadConfiguration().multipartConfig();
    assertEquals(100L * 1024 * 1024, element.getMaxFileSize());
    assertEquals(101L * 1024 * 1024, element.getMaxRequestSize());
  }
}
